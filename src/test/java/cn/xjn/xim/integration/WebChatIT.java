package cn.xjn.xim.integration;

import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.util.SessionManager;
import cn.xjn.xim.web.WebServerChannelInitializer;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.Assert.*;

/** Real HTTP/WebSocket clients test the browser API and interoperability with TCP. */
public class WebChatIT {
    private NioEventLoopGroup boss;
    private NioEventLoopGroup worker;
    private Channel listener;
    private HttpClient http;
    private URI base;
    private final List<Peer> peers = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();

    @Before
    public void start() throws Exception {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup(1);
        listener = new ServerBootstrap().group(boss, worker).channel(NioServerSocketChannel.class)
                .childHandler(new WebServerChannelInitializer()).bind("127.0.0.1", 0).sync().channel();
        base = URI.create("http://127.0.0.1:" + ((InetSocketAddress) listener.localAddress()).getPort());
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @After
    public void stop() throws Exception {
        for (Peer peer : peers) peer.close();
        if (listener != null) listener.close().sync();
        if (worker != null) worker.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        if (boss != null) boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        groups.forEach(SessionManager::unbindChannelGroup);
        for (Peer peer : peers) if (peer.userId != null) assertNull(SessionManager.getChannel(peer.userId));
        if (http != null) http.close();
    }

    @Test(timeout = 15000)
    public void servesPageAssetsAndRejectsUnknownPathsAndMethods() throws Exception {
        var page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("登录，开始聊天"));
        assertTrue(page.headers().firstValue("Content-Type").orElseThrow().contains("text/html"));
        assertTrue(get("/app.js").body().contains("new WebSocket"));
        assertTrue(get("/styles.css").body().contains("@media"));
        assertEquals(404, get("/../pom.xml").statusCode());
        assertEquals(404, get("/missing").statusCode());
        assertEquals(405, http.send(HttpRequest.newBuilder(base).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var head = http.send(HttpRequest.newBuilder(base).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(page.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                Integer.parseInt(head.headers().firstValue("Content-Length").orElseThrow()));
    }

    @Test(timeout = 15000)
    public void authenticatesRetriesListsUsersAndRoutesUnicodeMessagesWithAcknowledgement() throws Exception {
        Peer a = peer(); Peer b = peer();
        a.send("login", "username", "root", "password", "wrong");
        assertFalse(a.receive("login").getBooleanValue("success"));
        String aId = a.login("root"); String bId = b.login("aaa");
        a.send("users");
        assertTrue(a.receive("users").getJSONArray("users").stream().map(Object::toString).anyMatch(value -> value.contains(bId)));
        a.send("message", "toUserId", bId, "message", "你好 🌍\n<script>alert(1)</script>", "requestId", "m1", "fromUserId", "forged");
        JSONObject incoming = b.receive("message");
        assertEquals(aId, incoming.getString("fromUserId"));
        assertEquals("root", incoming.getString("fromUsername"));
        assertEquals("你好 🌍\n<script>alert(1)</script>", incoming.getString("message"));
        assertEquals("m1", a.receive("sent").getString("requestId"));
        a.send("message", "toUserId", "offline", "message", "未发送", "requestId", "m2");
        assertEquals("m2", a.receive("error").getString("requestId"));
        a.send("message", "toUserId", bId, "message", " "); assertTrue(a.receive("error").getString("reason").contains("不能为空"));
        a.send("message", "toUserId", bId, "message", "a".repeat(4001)); a.receive("error");
        a.send("heartbeat"); a.receive("heartbeat");
    }

    @Test(timeout = 15000)
    public void guardsAuthenticationInvalidInputAndLogoutWithoutDroppingTheSocket() throws Exception {
        Peer a = peer();
        a.send("users"); assertEquals("请先登录", a.receive("error").getString("reason"));
        a.socket.sendText("{broken", true).join(); a.receive("error");
        a.socket.sendText("null", true).join(); a.receive("error");
        a.send("login", "username", List.of("root"), "password", "root"); a.receive("error");
        String id = a.login("root");
        a.send("login", "username", "aaa", "password", "aaa"); a.receive("error");
        assertEquals("root", SessionManager.getSession(SessionManager.getChannel(id)).getUsername());
        a.send("unknown"); a.receive("error");
        a.send("logout"); assertTrue(a.receive("logout").getBooleanValue("success"));
        assertNull(SessionManager.getChannel(id));
        a.send("users"); a.receive("error");
        assertNotEquals(id, a.login("aaa"));
    }

    @Test(timeout = 15000)
    public void createsJoinsListsAndQuitsGroupsWithMembershipChecksAndBroadcasts() throws Exception {
        Peer a = peer(); Peer b = peer(); Peer c = peer();
        String aId = a.login("root"); String bId = b.login("aaa"); c.login("bbb");
        a.send("createGroup", "userIds", List.of(bId));
        String group = a.receive("createGroup").getString("groupId"); groups.add(group);
        assertEquals(group, b.receive("createGroup").getString("groupId"));
        c.send("groupMessage", "groupId", group, "message", "forbidden"); c.receive("error");
        c.send("members", "groupId", group); c.receive("error");
        c.send("joinGroup", "groupId", "missing"); assertFalse(c.receive("joinGroup").getBooleanValue("success"));
        c.send("joinGroup", "groupId", group); assertTrue(c.receive("joinGroup").getBooleanValue("success"));
        a.send("members", "groupId", group); assertEquals(3, a.receive("members").getJSONArray("sessions").size());
        a.send("groupMessage", "groupId", group, "message", "群聊 🌍", "requestId", "g1");
        for (Peer member : List.of(a, b, c)) {
            JSONObject message = member.receive("groupMessage");
            assertEquals("群聊 🌍", message.getString("message")); assertEquals(aId, message.getString("fromUserId"));
        }
        a.receive("sent");
        c.send("quitGroup", "groupId", group); assertTrue(c.receive("quitGroup").getBooleanValue("success"));
        c.send("groupMessage", "groupId", group, "message", "after quit"); c.receive("error");
        b.close(); awaitOffline(bId);
        a.send("members", "groupId", group); assertEquals(1, a.receive("members").getJSONArray("sessions").size());
        a.send("members", "groupId", "missing"); a.receive("error");
    }

    @Test(timeout = 15000)
    public void webAndTcpClientsExchangePrivateAndGroupMessages() throws Exception {
        try (TcpTestServer tcp = new TcpTestServer(); TcpPeer terminal = new TcpPeer(tcp.port())) {
            Peer browser = peer(); String browserId = browser.login("root");
            String terminalId = terminal.login("aaa").getUserId();
            browser.send("message", "toUserId", terminalId, "message", "web → TCP", "requestId", "mix");
            assertEquals("web → TCP", terminal.receive(MessageResponsePacket.class).getMessage()); browser.receive("sent");
            terminal.send(new MessageRequestPacket(browserId, "TCP → web")); assertEquals("TCP → web", browser.receive("message").getString("message"));
            browser.send("createGroup", "userIds", List.of(terminalId));
            String group = browser.receive("createGroup").getString("groupId"); groups.add(group);
            assertEquals(group, terminal.receive(CreateGroupResponsePacket.class).getGroupId());
            terminal.send(new GroupMessageRequestPacket(group, "混合群聊"));
            assertEquals("混合群聊", browser.receive("groupMessage").getString("message"));
            assertEquals(terminalId, terminal.receive(GroupMessageResponsePacket.class).getFromUserId());
        }
    }

    @Test(timeout = 15000)
    public void rejectsCrossOriginWebSocketHandshakes() throws Exception {
        try {
            http.newWebSocketBuilder().header("Origin", "https://other.example")
                    .buildAsync(URI.create(base.toString().replace("http:", "ws:") + "/ws"), new WebSocket.Listener() {}).join();
            fail("Cross-origin handshake accepted");
        } catch (CompletionException rejected) {
            assertTrue(rejected.getCause() instanceof WebSocketHandshakeException);
            assertEquals(403, ((WebSocketHandshakeException) rejected.getCause()).getResponse().statusCode());
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private Peer peer() {
        Peer peer = new Peer(); peers.add(peer);
        peer.socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create(base.toString().replace("http:", "ws:") + "/ws"), peer).join();
        return peer;
    }
    private void awaitOffline(String id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (SessionManager.getChannel(id) != null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNull(SessionManager.getChannel(id));
    }
    private static class Peer implements WebSocket.Listener, AutoCloseable {
        private final BlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        private final StringBuilder fragments = new StringBuilder();
        private WebSocket socket;
        private String userId;
        private boolean closed;

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) { inbox.add(JSON.parseObject(fragments.toString())); fragments.setLength(0); }
            webSocket.request(1); return null;
        }
        void send(String type, Object... fields) {
            JSONObject json = new JSONObject(); json.put("type", type);
            for (int i = 0; i < fields.length; i += 2) json.put((String) fields[i], fields[i + 1]);
            socket.sendText(json.toJSONString(), true).join();
        }
        JSONObject receive(String type) throws InterruptedException {
            JSONObject json = inbox.poll(4, TimeUnit.SECONDS); assertNotNull("No WebSocket response: " + type, json);
            assertEquals(json.toJSONString(), type, json.getString("type")); return json;
        }
        String login(String username) throws InterruptedException {
            send("login", "username", username, "password", username);
            JSONObject response = receive("login"); assertTrue(response.getBooleanValue("success"));
            userId = response.getString("userId"); return userId;
        }
        @Override public void close() {
            if (socket != null && !closed) { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); closed = true; }
        }
    }
}
