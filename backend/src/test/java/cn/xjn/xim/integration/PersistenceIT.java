package cn.xjn.xim.integration;

import cn.xjn.xim.protobuf.ProtocolWire;
import cn.xjn.xim.server.NettyServer;
import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import com.alibaba.fastjson.*;
import org.junit.Test;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Two independent server JVMs reuse a real file; TCP and WebSocket both recover and enforce access. */
public class PersistenceIT {
    @Test(timeout = 60000) public void offlineUnreadBeyondOnePageSurvivesRestartFreshLoginAndReading() throws Exception {
        Path database=Path.of("target","unread-"+UUID.randomUUID()+".db").toAbsolutePath();
        String aliceId,bobId,group; long privateSequence;
        try (HttpClient http=HttpClient.newHttpClient()) {
            try (var server=new JavaAppProcess(NettyServer.class,0,1,0,database)) {
                int webPort=port(server.awaitLog("Web API started",10),"Web API started, port");
                try (var alice=new Browser(http,webPort)) {
                    aliceId=alice.login("root");
                    try (var bob=new Browser(http,webPort)) {
                        bobId=bob.login("aaa"); bob.send("logout"); bob.receive("logout");
                    }
                    alice.send("createGroup","userIds",List.of(bobId)); group=alice.receive("createGroup").getString("groupId");
                    privateSequence=0;
                    for (int i=0;i<65;i++) {
                        Thread.sleep(75); // Two requests per iteration respect production request limits.
                        alice.send("message","toUserId",bobId,"message","离线私聊 "+i,"requestId","offline-p-"+i);
                        privateSequence=alice.receive("sent").getLongValue("sequence");
                        alice.send("groupMessage","groupId",group,"message","离线群聊 "+i,"requestId","offline-g-"+i);
                        alice.receive("groupMessage"); alice.receive("sent");
                    }
                    alice.send("message","toUserId",bobId,"message","离线私聊 64","requestId","offline-p-64");
                    assertEquals(privateSequence,alice.receive("sent").getLongValue("sequence"));
                }
            }
            try (var server=new JavaAppProcess(NettyServer.class,0,1,0,database)) {
                int webPort=port(server.awaitLog("Web API started",10),"Web API started, port");
                try (var bob=new Browser(http,webPort)) {
                    assertEquals(bobId,bob.login("aaa")); bob.send("conversations"); var snapshot=bob.receive("conversations");
                    assertEquals(65,summary(snapshot,"user",aliceId).getLongValue("unread"));
                    assertEquals(65,summary(snapshot,"group",group).getLongValue("unread"));
                    assertEquals("离线私聊 64",summary(snapshot,"user",aliceId).getJSONObject("lastMessage").getString("message"));
                    assertEquals(group,snapshot.getJSONArray("conversations").getJSONObject(0).getString("id"));
                    bob.send("message","toUserId",aliceId,"message","自己的回复","requestId","own-p"); bob.receive("sent");
                    bob.send("groupMessage","groupId",group,"message","自己的群回复","requestId","own-g"); bob.receive("groupMessage"); bob.receive("sent");
                    bob.send("conversations"); snapshot=bob.receive("conversations");
                    assertEquals(65,summary(snapshot,"user",aliceId).getLongValue("unread"));
                    assertEquals(65,summary(snapshot,"group",group).getLongValue("unread"));
                    bob.send("history","kind","group","id",group,"limit",50,"requestId","latest"); var latest=bob.receive("history");
                    assertEquals(50,latest.getJSONArray("messages").size()); assertTrue(latest.getBooleanValue("hasMore"));
                    long groupSequence=latest.getJSONArray("messages").getJSONObject(49).getLongValue("sequence");
                    bob.send("history","kind","group","id",group,"limit",50,"before",latest.getString("nextBefore"));
                    var earlier=bob.receive("history"); assertEquals(16,earlier.getJSONArray("messages").size()); assertFalse(earlier.getBooleanValue("hasMore"));
                    bob.send("conversations"); assertEquals(65,summary(bob.receive("conversations"),"group",group).getLongValue("unread"));
                    bob.send("read","kind","group","id",group,"sequence",Long.toString(groupSequence),"requestId","group-read");
                    assertEquals(0,bob.receive("read").getJSONObject("conversation").getLongValue("unread"));
                    bob.send("read","kind","group","id",group,"sequence",earlier.getJSONArray("messages").getJSONObject(0).getString("sequence"));
                    assertEquals(groupSequence,bob.receive("read").getJSONObject("conversation").getLongValue("readSequence"));
                    bob.send("read","kind","user","id",aliceId,"sequence",Long.toString(privateSequence));
                    assertEquals(0,bob.receive("read").getJSONObject("conversation").getLongValue("unread"));
                    bob.send("read","kind","user","id",aliceId,"sequence",Long.toString(groupSequence)); bob.receive("error");
                    bob.send("history","kind","user","id",aliceId,"after",Long.toString(privateSequence),"limit",1);
                    var sync=bob.receive("history"); assertEquals("自己的回复",sync.getJSONArray("messages").getJSONObject(0).getString("message"));
                    assertNotNull(sync.getString("nextAfter")); assertTrue(sync.getLongValue("syncCursor")>=groupSequence);
                }
                // A new browser connection represents refresh: no local conversation cache is reused.
                try (var refreshed=new Browser(http,webPort)) {
                    assertEquals(bobId,refreshed.login("aaa")); refreshed.send("conversations"); var snapshot=refreshed.receive("conversations");
                    assertEquals(0,summary(snapshot,"user",aliceId).getLongValue("unread"));
                    assertEquals(0,summary(snapshot,"group",group).getLongValue("unread"));
                }
            }
            try (var server=new JavaAppProcess(NettyServer.class,0,1,0,database)) {
                int webPort=port(server.awaitLog("Web API started",10),"Web API started, port");
                try (var restored=new Browser(http,webPort)) {
                    assertEquals(bobId,restored.login("aaa")); restored.send("conversations"); var snapshot=restored.receive("conversations");
                    assertEquals(0,summary(snapshot,"user",aliceId).getLongValue("unread"));
                    assertEquals(0,summary(snapshot,"group",group).getLongValue("unread"));
                    assertEquals("自己的群回复",summary(snapshot,"group",group).getJSONObject("lastMessage").getString("message"));
                }
            }
        }
    }
    @Test(timeout = 40000) public void serviceRestartRestoresIdentitiesMessagesMembershipsAndRouting() throws Exception {
        Path database = Path.of("target", "restart-" + UUID.randomUUID() + ".db").toAbsolutePath();
        String rootId, aaaId, group, privateId, groupMessageId, tcpPrivateId, tcpGroupId;
        long privateTime, groupTime;
        try (HttpClient http = HttpClient.newHttpClient()) {
            try (var server = new JavaAppProcess(NettyServer.class,0,1,0,database)) {
                int tcpPort = port(server.awaitLog("started up successfully",10),"port");
                int webPort = port(server.awaitLog("Web API started",10),"Web API started, port");
                try (var browser = new Browser(http,webPort); var tcp = new TcpPeer(tcpPort)) {
                    rootId = browser.login("root"); aaaId = tcp.login("aaa").getUserId();
                    browser.send("message","toUserId",aaaId,"message","私聊 🌍","requestId","p1");
                    var incoming = tcp.receive(MessageResponsePacket.class);
                    var ack = browser.receive("sent"); assertEquals(incoming.getId(),ack.getString("id"));
                    privateId = ack.getString("id"); privateTime = ack.getLongValue("time");
                    // Treat the original receipt as lost and resend the same logical message.
                    browser.send("message","toUserId",aaaId,"message","私聊 🌍","requestId","p1");
                    assertEquals(privateId, browser.receive("sent").getString("id"));
                    var privateQuery = new HistoryRequestPacket(); privateQuery.setKind("user"); privateQuery.setId(rootId);
                    tcp.send(privateQuery); assertEquals(1, tcp.receive(HistoryResponsePacket.class).getMessages().size());
                    var tcpPrivate = new MessageRequestPacket(rootId,"TCP 回复"); tcpPrivate.setRequestId("tcp-private");
                    tcp.send(tcpPrivate); browser.receive("message"); tcpPrivateId = tcp.receive(SentResponsePacket.class).getId();
                    tcp.send(tcpPrivate); assertEquals(tcpPrivateId, tcp.receive(SentResponsePacket.class).getId());
                    browser.send("createGroup","userIds",List.of(aaaId));
                    group = browser.receive("createGroup").getString("groupId"); tcp.receive(CreateGroupResponsePacket.class);
                    var tcpGroup = new GroupMessageRequestPacket(group,"重启前群聊"); tcpGroup.setRequestId("tcp-group");
                    tcp.send(tcpGroup); browser.receive("groupMessage"); tcp.receive(GroupMessageResponsePacket.class);
                    tcpGroupId = tcp.receive(SentResponsePacket.class).getId();
                    tcp.send(tcpGroup); assertEquals(tcpGroupId, tcp.receive(SentResponsePacket.class).getId());
                    // Explicit logout removes only online state. Store messages while recipient is offline.
                    tcp.send(new LogoutRequestPacket()); tcp.receive(LogoutResponsePacket.class);
                    browser.send("message","toUserId",aaaId,"message","离线私聊","requestId","p2"); browser.receive("sent");
                    browser.send("groupMessage","groupId",group,"message","离线群聊","requestId","g2");
                    var echo = browser.receive("groupMessage"); var groupAck = browser.receive("sent");
                    groupMessageId = groupAck.getString("id"); groupTime = groupAck.getLongValue("time");
                    assertEquals("g2", echo.getString("requestId"));
                    browser.send("groupMessage","groupId",group,"message","离线群聊","requestId","g2");
                    assertEquals(groupMessageId, browser.receive("sent").getString("id"));
                    browser.send("conversations"); var beforeRead=browser.receive("conversations");
                    var privateSummary=summary(beforeRead,"user",aaaId); assertEquals(1,privateSummary.getLongValue("unread"));
                    assertEquals(1,summary(beforeRead,"group",group).getLongValue("unread"));
                    browser.send("read","kind","user","id",aaaId,"sequence",privateSummary.getString("lastSequence"),"requestId","read-before-restart");
                    assertEquals(0,browser.receive("read").getJSONObject("conversation").getLongValue("unread"));
                    // Fresh login on a replacement browser restores the committed reading position.
                    try (var refreshed=new Browser(http,webPort)) {
                        assertEquals(rootId,refreshed.login("root")); refreshed.send("conversations");
                        assertEquals(0,summary(refreshed.receive("conversations"),"user",aaaId).getLongValue("unread"));
                    }
                }
            }
            assertTrue(Files.size(database) > 0);
            try (var server = new JavaAppProcess(NettyServer.class,0,1,0,database)) {
                int tcpPort = port(server.awaitLog("started up successfully",10),"port");
                int webPort = port(server.awaitLog("Web API started",10),"Web API started, port");
                try (var browser = new Browser(http,webPort); var tcp = new TcpPeer(tcpPort); var outsider = new Browser(http,webPort)) {
                    assertEquals(rootId,browser.login("root"));
                    browser.send("users"); assertEquals(1,browser.receive("users").getJSONArray("users").size());
                    browser.send("conversations");
                    var snapshot=browser.receive("conversations");
                    String restored = snapshot.getJSONArray("conversations").toJSONString();
                    assertEquals(0,summary(snapshot,"user",aaaId).getLongValue("unread"));
                    assertEquals(1,summary(snapshot,"group",group).getLongValue("unread"));
                    assertEquals("离线群聊",summary(snapshot,"group",group).getJSONObject("lastMessage").getString("message"));
                    assertEquals(group,snapshot.getJSONArray("conversations").getJSONObject(0).getString("id"));
                    assertTrue(restored.contains(group)); assertTrue(restored.contains(aaaId));
                    browser.send("history","kind","user","id",aaaId,"limit",50);
                    var history = browser.receive("history").getJSONArray("messages");
                    assertEquals(List.of("私聊 🌍","TCP 回复","离线私聊"), bodies(history));
                    assertTrue(history.getJSONObject(0).getLongValue("time") > 0);
                    assertEquals(aaaId,tcp.login("aaa").getUserId());
                    tcp.send(new ConversationsRequestPacket()); var bobSnapshot=tcp.receive(ConversationsResponsePacket.class);
                    assertEquals(2,bobSnapshot.getConversations().stream().filter(chat -> chat.getKind().equals("user") && chat.getId().equals(rootId)).findFirst().orElseThrow().getUnread());
                    assertEquals(1,bobSnapshot.getConversations().stream().filter(chat -> chat.getKind().equals("group") && chat.getId().equals(group)).findFirst().orElseThrow().getUnread());
                    // Replay after a real JVM restart: same IDs/times, no repeated routing.
                    browser.send("message","toUserId",aaaId,"message","私聊 🌍","requestId","p1");
                    var privateAck = browser.receive("sent"); assertEquals(privateId,privateAck.getString("id")); assertEquals(privateTime,privateAck.getLongValue("time"));
                    browser.send("groupMessage","groupId",group,"message","离线群聊","requestId","g2");
                    var groupAck = browser.receive("sent"); assertEquals(groupMessageId,groupAck.getString("id")); assertEquals(groupTime,groupAck.getLongValue("time"));
                    browser.send("message","toUserId",aaaId,"message","changed","requestId","p1"); browser.receive("error");
                    browser.send("groupMessage","groupId",group,"message","私聊 🌍","requestId","p1"); browser.receive("error");
                    var query = new HistoryRequestPacket(); query.setKind("group"); query.setId(group); query.setLimit(1);
                    tcp.send(query); var recent = tcp.receive(HistoryResponsePacket.class); // A duplicate broadcast would fail this barrier.
                    assertEquals("g2", recent.getMessages().getFirst().getClientMessageId());
                    var readGroup=new ReadRequestPacket(); readGroup.setKind("group"); readGroup.setId(group);
                    readGroup.setSequence(Long.toString(recent.getMessages().getLast().getSequence())); readGroup.setRequestId("tcp-read");
                    tcp.send(readGroup); var readAck=tcp.receive(ReadResponsePacket.class);
                    assertEquals("tcp-read",readAck.getRequestId()); assertEquals(0,readAck.getConversation().getUnread());
                    assertTrue(recent.isHasMore()); assertEquals("离线群聊",recent.getMessages().getFirst().getMessage());
                    query.setBefore(recent.getNextBefore()); tcp.send(query);
                    assertEquals("重启前群聊",tcp.receive(HistoryResponsePacket.class).getMessages().getFirst().getMessage());
                    var tcpPrivateReplay = new MessageRequestPacket(rootId,"TCP 回复"); tcpPrivateReplay.setRequestId("tcp-private");
                    tcp.send(tcpPrivateReplay); assertEquals(tcpPrivateId,tcp.receive(SentResponsePacket.class).getId());
                    var tcpGroupReplay = new GroupMessageRequestPacket(group,"重启前群聊"); tcpGroupReplay.setRequestId("tcp-group");
                    tcp.send(tcpGroupReplay); assertEquals(tcpGroupId,tcp.receive(SentResponsePacket.class).getId());
                    browser.send("members","groupId",group); assertEquals(2,browser.receive("members").getJSONArray("sessions").size());
                    tcp.send(new GroupMessageRequestPacket(group,"恢复路由"));
                    assertEquals("恢复路由",browser.receive("groupMessage").getString("message")); tcp.receive(GroupMessageResponsePacket.class);
                    outsider.login("bbb");
                    outsider.send("history","kind","group","id",group); outsider.receive("error");
                    outsider.send("history","kind","user","id",rootId); assertTrue(outsider.receive("history").getJSONArray("messages").isEmpty());
                    // TCP permissions must match WebSocket permissions.
                    var quit = new QuitGroupRequestPacket(); quit.setGroupId(group); tcp.send(quit); tcp.receive(QuitGroupResponsePacket.class);
                    tcp.send(query); assertTrue(tcp.receive(ErrorResponsePacket.class).getReason().contains("加入"));
                    tcp.send(new GroupMessageRequestPacket(group,"forbidden")); tcp.receive(ErrorResponsePacket.class);
                }
            }
        }
    }
    private static JSONObject summary(JSONObject snapshot,String kind,String id) {
        for (Object value:snapshot.getJSONArray("conversations")) {
            var chat=(JSONObject)value; if (kind.equals(chat.getString("kind")) && id.equals(chat.getString("id"))) return chat;
        }
        throw new AssertionError("Missing conversation " + kind + ":" + id);
    }
    private static int port(String log, String prefix) {
        var matcher = Pattern.compile(Pattern.quote(prefix)+"\\[(\\d+)]").matcher(log);
        assertTrue(log,matcher.find()); return Integer.parseInt(matcher.group(1));
    }
    private static List<String> bodies(JSONArray messages) {
        List<String> result = new ArrayList<>();
        for (int i=0;i<messages.size();i++) result.add(messages.getJSONObject(i).getString("message"));
        return result;
    }
    private static class Browser implements WebSocket.Listener, AutoCloseable {
        private final BlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        private final java.io.ByteArrayOutputStream fragments = new java.io.ByteArrayOutputStream(); private final WebSocket socket;
        Browser(HttpClient http,int port) {
            socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws"),this).join();
        }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]; data.get(bytes); fragments.writeBytes(bytes);
            if (last) { inbox.add(ProtocolWire.decodeWeb(fragments.toByteArray(), false)); fragments.reset(); }
            ws.request(1); return null;
        }
        void send(String type,Object... fields) {
            JSONObject message = new JSONObject(); message.put("type",type);
            for(int i=0;i<fields.length;i+=2) message.put((String)fields[i],fields[i+1]);
            socket.sendBinary(java.nio.ByteBuffer.wrap(ProtocolWire.encodeWeb(message, true)), true).join();
        }
        JSONObject receive(String type) throws InterruptedException {
            var result = inbox.poll(5,TimeUnit.SECONDS); assertNotNull("Missing "+type,result);
            assertEquals(result.toJSONString(),type,result.getString("type")); return result;
        }
        String login(String user) throws InterruptedException {
            send("login","username",user,"password",user); var response=receive("login");
            assertTrue(response.getBooleanValue("success")); return response.getString("userId");
        }
        @Override public void close() { if (!socket.isOutputClosed()) socket.sendClose(WebSocket.NORMAL_CLOSURE,"done").join(); }
    }
}
