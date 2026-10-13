package cn.xjn.xim.integration;

import cn.xjn.xim.protobuf.ProtocolWire;
import cn.xjn.xim.server.NettyServer;
import com.alibaba.fastjson.*;
import org.junit.Test;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Real authenticated WebSocket API, isolated production-mode process and SQLite file. */
public class ContactsIT {
    @Test(timeout = 40000) public void directoryPrivacyPaginationFirstOfflineChatAndOfflineGroupRecovery() throws Exception {
        var dir = Files.createTempDirectory(Path.of("target"), "contacts-api-");
        try (var server = new JavaAppProcess(NettyServer.class, 0, 1, 0, dir.resolve("chat.db"), false);
             var http = HttpClient.newHttpClient()) {
            server.awaitLog("Web API started", 10);
            var match = Pattern.compile("Web API started, port\\[(\\d+)]").matcher(server.output());
            assertTrue(match.find()); var base = URI.create("http://127.0.0.1:" + match.group(1));
            try (var alice = new Peer(http, base); var bob = new Peer(http, base); var carol = new Peer(http, base); var dave = new Peer(http, base)) {
                alice.send("contacts", "requestId", "unauth");
                assertEquals("请先登录", alice.receive("error").getString("reason"));
                for (var item : List.of(Map.entry(alice, "alice"), Map.entry(bob, "bob"), Map.entry(carol, "carol"), Map.entry(dave, "dave"))) {
                    item.getKey().send("register", "username", item.getValue() + "_user", "password", "private-test-password",
                            "displayName", item.getValue().equals("bob") ? "小林_100%" : item.getValue(), "requestId", "reg-" + item.getValue());
                    assertTrue(item.getKey().receive("register").getBooleanValue("success"));
                }
                String a = alice.login("alice_user"); String c = carol.login("carol_user");
                alice.send("conversations"); assertTrue(alice.receive("conversations").getJSONArray("conversations").isEmpty());
                List<JSONObject> all = new ArrayList<>(); String after = null; int pages = 0;
                do {
                    List<Object> fields = new ArrayList<>(List.of("requestId", "page-" + pages, "limit", 1, "query", "_user"));
                    if (after != null) { fields.add("after"); fields.add(after); }
                    alice.send("contacts", fields.toArray());
                    var response = alice.receive("contacts"); assertEquals("page-" + pages, response.getString("requestId"));
                    assertEquals(1, response.getJSONArray("users").size());
                    var user = response.getJSONArray("users").getJSONObject(0);
                    assertEquals(Set.of("userId", "username", "displayName", "online"), user.keySet());
                    assertNotEquals(a, user.getString("userId")); all.add(user);
                    after = response.getString("nextAfter"); pages++;
                    assertEquals(after != null, response.getBooleanValue("hasMore"));
                } while (after != null);
                assertEquals(3, pages); assertEquals(3, all.stream().map(u -> u.getString("userId")).distinct().count());
                var b = all.stream().filter(u -> u.getString("username").equals("bob_user")).findFirst().orElseThrow();
                var d = all.stream().filter(u -> u.getString("username").equals("dave_user")).findFirst().orElseThrow();
                assertFalse(b.getBooleanValue("online")); assertTrue(all.stream().filter(u -> u.getString("userId").equals(c)).findFirst().orElseThrow().getBooleanValue("online"));
                for (String query : List.of("BOB_USER", "小林_100%")) {
                    alice.send("contacts", "query", query, "requestId", "search");
                    var users = alice.receive("contacts").getJSONArray("users");
                    assertEquals(1, users.size()); assertEquals(b.getString("userId"), users.getJSONObject(0).getString("userId"));
                }
                alice.send("contacts", "query", "missing", "requestId", "empty");
                assertTrue(alice.receive("contacts").getJSONArray("users").isEmpty());
                for (Object limit : List.of(0, 51)) {
                    alice.send("contacts", "limit", limit, "requestId", "bad");
                    assertEquals("bad", alice.receive("error").getString("requestId"));
                }
                alice.send("contacts", "query", "x".repeat(65), "requestId", "long"); alice.receive("error");
                alice.socket.sendBinary(java.nio.ByteBuffer.wrap(new byte[]{(byte)0x80}), true).join(); alice.receive("error");
                alice.send("contacts"); alice.receive("error");
                alice.send("message", "toUserId", "missing-user", "message", "bad", "requestId", "missing");
                assertEquals("missing", alice.receive("error").getString("requestId"));
                alice.send("history", "kind", "user", "id", "missing-user", "requestId", "missing-history"); alice.receive("error");
                alice.send("message", "toUserId", a, "message", "self", "requestId", "self"); alice.receive("error");
                alice.send("message", "toUserId", b.getString("userId"), "message", "首次离线私聊", "requestId", "first-offline");
                String committed = alice.receive("sent").getString("id"); assertNotNull(committed);
                alice.send("createGroup", "userIds", List.of(b.getString("userId"), d.getString("userId")));
                String group = alice.receive("createGroup").getString("groupId");
                alice.send("groupMessage", "groupId", group, "message", "离线群成员留言", "requestId", "offline-group");
                alice.receive("groupMessage"); alice.receive("sent");
                alice.send("createGroup", "userIds", List.of("missing-user")); alice.receive("error");
                assertEquals(b.getString("userId"), bob.login("bob_user"));
                bob.send("conversations"); var chats = bob.receive("conversations").getJSONArray("conversations");
                assertEquals(2, chats.size());
                for (Object value : chats) assertEquals(1, ((JSONObject)value).getIntValue("unread"));
                bob.send("history", "kind", "user", "id", a, "limit", 20, "requestId", "private");
                var history = bob.receive("history").getJSONArray("messages"); assertEquals(1, history.size());
                assertEquals(committed, history.getJSONObject(0).getString("id")); assertEquals("首次离线私聊", history.getJSONObject(0).getString("message"));
                bob.send("history", "kind", "group", "id", group, "limit", 20, "requestId", "group");
                assertEquals("离线群成员留言", bob.receive("history").getJSONArray("messages").getJSONObject(0).getString("message"));
                bob.send("members", "groupId", group); assertEquals(3, bob.receive("members").getJSONArray("sessions").size());
                carol.send("history", "kind", "group", "id", group, "requestId", "forbidden"); carol.receive("error");
                alice.send("contacts", "query", "bob_user", "requestId", "now-online");
                assertTrue(alice.receive("contacts").getJSONArray("users").getJSONObject(0).getBooleanValue("online"));
                alice.send("conversations");
                var restored = alice.receive("conversations").getJSONArray("conversations");
                assertEquals(1, restored.stream().filter(value -> ((JSONObject)value).getString("kind").equals("user")).count());
                var privateChat = restored.stream().map(value -> (JSONObject)value).filter(value -> value.getString("kind").equals("user")).findFirst().orElseThrow();
                assertEquals("小林_100%", privateChat.getString("name"));
                carol.send("logout"); carol.receive("logout");
                carol.send("contacts", "requestId", "after-logout"); carol.receive("error");
                // Break only the directory query in this disposable DB; return a correlated,
                // redacted failure rather than an empty page or leaking SQL/driver details.
                try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("chat.db"));
                     var sql = conn.createStatement()) {
                    sql.execute("ALTER TABLE xim_users RENAME COLUMN display_name TO unavailable_name");
                }
                alice.send("contacts", "requestId", "storage-failure");
                var failure = alice.receive("error");
                assertEquals("storage-failure", failure.getString("requestId"));
                assertEquals("联系人查询失败，请稍后重试", failure.getString("reason"));
            }
            assertFalse(server.output().contains("private-test-password"));
            assertFalse(server.output().contains("pbkdf2-sha256$"));
        } finally { try (var paths = Files.walk(dir)) { for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
    }
    private static final class Peer implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        private final java.io.ByteArrayOutputStream fragments = new java.io.ByteArrayOutputStream(); final WebSocket socket;
        Peer(HttpClient http, URI base) {
            socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(URI.create(base.toString().replace("http:", "ws:") + "/ws"), this).join();
        }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]; data.get(bytes); fragments.writeBytes(bytes);
            if (last) { inbox.add(ProtocolWire.decodeWeb(fragments.toByteArray(), false)); fragments.reset(); }
            ws.request(1); return null;
        }
        void send(String type, Object... fields) {
            var data = new JSONObject(); data.put("type", type);
            for (int i = 0; i < fields.length; i += 2) data.put((String)fields[i], fields[i + 1]);
            socket.sendBinary(java.nio.ByteBuffer.wrap(ProtocolWire.encodeWeb(data, true)), true).join();
        }
        JSONObject receive(String type) throws Exception {
            var data = inbox.poll(5, TimeUnit.SECONDS); assertNotNull("Missing " + type, data);
            assertEquals(data.toJSONString(), type, data.getString("type")); return data;
        }
        String login(String username) throws Exception {
            send("login", "username", username, "password", "private-test-password");
            var response = receive("login"); assertTrue(response.getBooleanValue("success")); return response.getString("userId");
        }
        @Override public void close() { if (!socket.isOutputClosed()) socket.sendClose(1000, "done").join(); }
    }
}
