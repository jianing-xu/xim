package cn.xjn.xim.integration;

import cn.xjn.xim.protobuf.ProtocolWire;
import cn.xjn.xim.server.NettyServer;
import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import com.alibaba.fastjson.*;
import org.junit.Test;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Real cookies, WebSocket connections, SQLite and independent service JVM restarts. */
public class SessionRecoveryIT {
    @Test(timeout=55000) public void cookieRestoresAfterRefreshAndRestartWithMoreThan50MissingMessagesAndLostReceipt() throws Exception {
        var dir=Files.createTempDirectory(Path.of("target"),"session-recovery-");var db=dir.resolve("chat.db");
        String cookie,id,peerId,group,savedId,connectionId;
        try {
            try(var server=new JavaAppProcess(NettyServer.class,0,1,0,db);var http=HttpClient.newHttpClient()) {
                var ports=ports(server);var base=URI.create("http://127.0.0.1:"+ports[1]);
                try(var a=new Peer(http,base,null);var b=new TcpPeer(ports[0])) {
                    var auth=a.login("root","root");id=auth.getString("userId");connectionId=auth.getString("connectionId");cookie=exchange(http,base,auth.getString("sessionTicket"));
                    assertEquals(401,post(http,base,auth.getString("sessionTicket"),null).statusCode());
                    peerId=b.login("aaa").getUserId();
                    var create=new CreateGroupRequestPacket();create.setUserIds(List.of(id,peerId));a.send("createGroup","userIds",List.of(peerId));
                    group=a.receive("createGroup").getString("groupId");b.receive(CreateGroupResponsePacket.class);
                    a.send("message","toUserId",peerId,"message","lost receipt","requestId","lost-receipt");
                    savedId=b.receive(MessageResponsePacket.class).getId(); // Commit is known, but browser never consumes its receipt.
                }
                try(var refreshed=new Peer(http,base,cookie)) {
                    refreshed.send("resume","connectionId",connectionId);assertEquals(id,refreshed.receive("resume").getString("userId"));
                }
                try(var sender=new TcpPeer(ports[0])) {
                    sender.login("aaa");
                    for(int i=0;i<65;i++) {
                        Thread.sleep(75); // Two requests per iteration respect production request limits.
                        var p=new MessageRequestPacket(id,"offline private "+i);p.setRequestId("offline-p-"+i);sender.send(p);sender.receive(SentResponsePacket.class);
                        var g=new GroupMessageRequestPacket(group,"offline group "+i);g.setRequestId("offline-g-"+i);sender.send(g);
                        sender.receive(GroupMessageResponsePacket.class);sender.receive(SentResponsePacket.class);
                    }
                }
                assertNoSecrets(server.output(),cookie);
            }
            try(var server=new JavaAppProcess(NettyServer.class,0,1,0,db);var http=HttpClient.newHttpClient()) {
                var ports=ports(server);var base=URI.create("http://127.0.0.1:"+ports[1]);
                try(var recovered=new Peer(http,base,cookie);var sender=new TcpPeer(ports[0])) {
                    recovered.send("resume");var auth=recovered.receive("resume");assertTrue(auth.getBooleanValue("success"));assertEquals(id,auth.getString("userId"));
                    recovered.send("conversations");var summaries=recovered.receive("conversations").getJSONArray("conversations");
                    assertEquals(2,summaries.size());for(Object value:summaries)assertEquals(65,((JSONObject)value).getIntValue("unread"));
                    var privateIds=pull(recovered,"user",peerId);assertEquals(66,privateIds.size());assertTrue(privateIds.contains(savedId));
                    assertEquals(65,pull(recovered,"group",group).size());
                    sender.login("aaa");
                    recovered.send("message","toUserId",peerId,"message","lost receipt","requestId","lost-receipt");
                    assertEquals(savedId,recovered.receive("sent").getString("id"));sender.heartbeat();
                    sender.send(new GroupMessageRequestPacket(group,"restored route"));sender.receive(GroupMessageResponsePacket.class);
                    assertEquals("restored route",recovered.receive("groupMessage").getString("message"));
                    recovered.send("logout");assertTrue(recovered.receive("logout").getBooleanValue("success"));
                }
                try(var loggedOut=new Peer(http,base,cookie)) {loggedOut.send("resume");assertFalse(loggedOut.receive("resume").getBooleanValue("success"));}
                assertNoSecrets(server.output(),cookie);
            }
        } finally {delete(dir);}
    }
    @Test(timeout=30000) public void activeWindowsDoNotResumeOverEachOtherAndExplicitLoginRevokesOldCookie() throws Exception {
        var dir=Files.createTempDirectory(Path.of("target"),"session-windows-");
        try(var server=new JavaAppProcess(NettyServer.class,0,1,0,dir.resolve("chat.db"));var http=HttpClient.newHttpClient()) {
            var base=URI.create("http://127.0.0.1:"+ports(server)[1]);
            try(var first=new Peer(http,base,null)) {
                var auth=first.login("root","root");var cookie=exchange(http,base,auth.getString("sessionTicket"));
                try(var second=new Peer(http,base,cookie)) {
                    second.send("resume");assertEquals("replaced",second.receive("sessionInvalid").getString("code"));
                    // A refresh/short break can transfer its exact previous connection lease once.
                    try(var refresh=new Peer(http,base,cookie)) {
                        refresh.send("resume","connectionId",auth.getString("connectionId"));assertTrue(refresh.receive("resume").getBooleanValue("success"));
                        assertEquals("replaced",first.receive("sessionInvalid").getString("code"));
                        second.send("resume","connectionId",auth.getString("connectionId"));assertEquals("replaced",second.receive("sessionInvalid").getString("code"));
                        var next=second.login("root","root");var newCookie=exchange(http,base,next.getString("sessionTicket"));
                        assertEquals("replaced",refresh.receive("sessionInvalid").getString("code"));
                        try(var stale=new Peer(http,base,cookie)) {stale.send("resume");assertFalse(stale.receive("resume").getBooleanValue("success"));}
                        var deleted=http.send(HttpRequest.newBuilder(URI.create(base+"/api/session")).header("Cookie",newCookie).DELETE().build(),HttpResponse.BodyHandlers.ofString());
                        assertEquals(204,deleted.statusCode());assertTrue(deleted.headers().firstValue("set-cookie").orElseThrow().contains("Max-Age=0"));
                        assertEquals("revoked",second.receive("sessionInvalid").getString("code"));
                        try(var afterDelete=new Peer(http,base,newCookie)) {afterDelete.send("resume");assertFalse(afterDelete.receive("resume").getBooleanValue("success"));}
                    }
                }
            }
        } finally {delete(dir);}
    }
    @Test(timeout=30000) public void expiryDisconnectsActiveSocketAndPasswordChangeRevokesResumeAcrossRestart() throws Exception {
        var dir=Files.createTempDirectory(Path.of("target"),"session-expiry-");var db=dir.resolve("chat.db");String cookie;
        try {
            try(var server=new JavaAppProcess(NettyServer.class,0,1,0,db,true,"-Dxim.session.ttlSeconds=2");var http=HttpClient.newHttpClient()) {
                var base=URI.create("http://127.0.0.1:"+ports(server)[1]);
                try(var first=new Peer(http,base,null)) {
                    var auth=first.login("root","root");var expiredCookie=exchange(http,base,auth.getString("sessionTicket"));
                    assertEquals("expired",first.receive("sessionInvalid").getString("code"));
                    try(var afterExpiry=new Peer(http,base,expiredCookie)) {afterExpiry.send("resume");assertFalse(afterExpiry.receive("resume").getBooleanValue("success"));}
                }
            }
            try(var server=new JavaAppProcess(NettyServer.class,0,1,0,db);var http=HttpClient.newHttpClient()) {
                var base=URI.create("http://127.0.0.1:"+ports(server)[1]);
                try(var a=new Peer(http,base,null)) {
                    var auth=a.login("root","root");cookie=exchange(http,base,auth.getString("sessionTicket"));
                    a.send("changePassword","currentPassword","root","newPassword","new-private-password","requestId","password-change");
                    assertTrue(a.receive("changePassword").getBooleanValue("success"));
                }
            }
            try(var server=new JavaAppProcess(NettyServer.class,0,1,0,db);var http=HttpClient.newHttpClient()) {
                var base=URI.create("http://127.0.0.1:"+ports(server)[1]);
                try(var a=new Peer(http,base,cookie)) {
                    a.send("resume");assertFalse(a.receive("resume").getBooleanValue("success"));
                    assertFalse(a.login("root","root").getBooleanValue("success"));assertTrue(a.login("root","new-private-password").getBooleanValue("success"));
                    a.send("revokeSessions");assertEquals("revoked",a.receive("sessionInvalid").getString("code"));
                }
            }
        } finally {delete(dir);}
    }
    @Test(timeout=15000) public void cookieExchangeRejectsCrossOriginFormsAndMalformedTickets() throws Exception {
        var dir=Files.createTempDirectory(Path.of("target"),"session-security-");
        try(var server=new JavaAppProcess(NettyServer.class,0,1,0,dir.resolve("chat.db"));var http=HttpClient.newHttpClient()) {
            var base=URI.create("http://127.0.0.1:"+ports(server)[1]);
            try(var a=new Peer(http,base,null)) {
                var ticket=a.login("root","root").getString("sessionTicket");
                assertEquals(403,post(http,base,ticket,"https://evil.example").statusCode());
                var endpoint=URI.create(base+"/api/session");
                var preflight=http.send(HttpRequest.newBuilder(endpoint).method("OPTIONS",HttpRequest.BodyPublishers.noBody())
                    .header("Origin","http://localhost:5173").header("Access-Control-Request-Method","POST")
                    .header("Access-Control-Request-Headers","content-type").header("Sec-Fetch-Site","same-site").build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(204,preflight.statusCode());assertEquals("http://localhost:5173",preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
                assertEquals("true",preflight.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
                assertEquals("Origin",preflight.headers().firstValue("Vary").orElseThrow());
                var crossSite=http.send(HttpRequest.newBuilder(endpoint).method("OPTIONS",HttpRequest.BodyPublishers.noBody())
                    .header("Origin","http://localhost:5173").header("Access-Control-Request-Method","POST").header("Sec-Fetch-Site","cross-site").build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(403,crossSite.statusCode());assertTrue(crossSite.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
                var form=http.send(HttpRequest.newBuilder(URI.create(base+"/api/session")).POST(HttpRequest.BodyPublishers.ofString("ticket="+ticket)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(415,form.statusCode());
                assertEquals(401,post(http,base,"forged",null).statusCode());
                var allowed=post(http,base,ticket,"http://localhost:5173");assertEquals(204,allowed.statusCode());
                assertEquals("http://localhost:5173",allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
                assertEquals("true",allowed.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
            }
        } finally {delete(dir);}
    }
    private static Set<String> pull(Peer peer,String kind,String id) throws Exception {
        String after="0";Set<String> ids=new HashSet<>();int pages=0;long previous=0;
        while(true) {
            peer.send("history","kind",kind,"id",id,"after",after,"limit",50,"requestId","page-"+pages);
            var page=peer.receive("history");pages++;
            for(Object value:page.getJSONArray("messages")) {var row=(JSONObject)value;assertTrue(row.getLongValue("sequence")>previous);previous=row.getLongValue("sequence");assertTrue(ids.add(row.getString("id")));}
            after=page.getString("nextAfter");if(!page.getBooleanValue("hasMore"))break;
        }
        assertEquals(2,pages);return ids;
    }
    private static HttpResponse<String> post(HttpClient http,URI base,String ticket,String origin) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+"/api/session")).header("Content-Type","application/json");if(origin!=null)request.header("Origin",origin);
        var body=new JSONObject();body.put("ticket",ticket);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toJSONString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String exchange(HttpClient http,URI base,String ticket) throws Exception {
        var response=post(http,base,ticket,null);assertEquals(204,response.statusCode());assertEquals("",response.body());
        var cookie=response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.startsWith("__Host-xim_session="));assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));assertTrue(cookie.contains("Secure"));assertFalse(cookie.contains("Domain="));
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());return cookie.split(";",2)[0];
    }
    private static int[] ports(JavaAppProcess server) throws Exception {
        server.awaitLog("Web API started",10);String logs=server.awaitLog("server started up successfully",10);var tcp=Pattern.compile("server started up successfully, port\\[(\\d+)]").matcher(logs);assertTrue(tcp.find());
        var web=Pattern.compile("Web API started, port\\[(\\d+)]").matcher(logs);assertTrue(logs,web.find());return new int[]{Integer.parseInt(tcp.group(1)),Integer.parseInt(web.group(1))};
    }
    private static void assertNoSecrets(String output,String cookie){assertFalse(output.contains(cookie.split("=",2)[1]));assertFalse(output.contains("new-private-password"));}
    private static void delete(Path dir) throws Exception {try(var paths=Files.walk(dir)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private static final class Peer implements WebSocket.Listener,AutoCloseable {
        private final BlockingQueue<JSONObject> inbox=new LinkedBlockingQueue<>();private final java.io.ByteArrayOutputStream fragments = new java.io.ByteArrayOutputStream();private final WebSocket socket;
        Peer(HttpClient http,URI base,String cookie){var builder=http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3));if(cookie!=null)builder.header("Cookie",cookie);socket=builder.buildAsync(URI.create(base.toString().replace("http:","ws:")+"/ws"),this).join();}
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onBinary(WebSocket ws, java.nio.ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]; data.get(bytes); fragments.writeBytes(bytes);
            if (last) { inbox.add(ProtocolWire.decodeWeb(fragments.toByteArray(), false)); fragments.reset(); }
            ws.request(1); return null;
        }
        void send(String type,Object...fields){var json=new JSONObject();json.put("type",type);for(int i=0;i<fields.length;i+=2)json.put((String)fields[i],fields[i+1]);socket.sendBinary(java.nio.ByteBuffer.wrap(ProtocolWire.encodeWeb(json, true)), true).join();}
        JSONObject receive(String type) throws Exception {var data=inbox.poll(5,TimeUnit.SECONDS);assertNotNull("Missing "+type,data);assertEquals(data.toJSONString(),type,data.getString("type"));return data;}
        JSONObject login(String username,String password) throws Exception {send("login","username",username,"password",password);return receive("login");}
        @Override public void close(){if(!socket.isOutputClosed())socket.sendClose(1000,"done").join();}
    }
}
