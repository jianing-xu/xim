package cn.xjn.xim.integration;
import cn.xjn.xim.server.NettyServer;
import cn.xjn.xim.protobuf.ProtocolWire;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.Assert.*;
public class ProtocolBoundaryIT {
    @Test(timeout=30000) public void realWireRejectsLegacyMalformedAndOversizeAndReleasesConnectionCapacity() throws Exception {
        try(var server=new JavaAppProcess(NettyServer.class,0,1,0,null,true,"-Dxim.connections.max=2","-Dxim.handshake.timeoutSeconds=1");var http=HttpClient.newHttpClient()) {
            String output=server.awaitLog("Web API started",10);var ports=Pattern.compile("(?:successfully, port|Web API started, port)\\[(\\d+)]").matcher(output);
            assertTrue(ports.find());int tcp=Integer.parseInt(ports.group(1));assertTrue(ports.find());int web=Integer.parseInt(ports.group(1));
            try(var first=new TcpPeer(tcp);var second=new TcpPeer(tcp)) {
                Thread.sleep(100);try(var denied=new TcpPeer(tcp)){denied.awaitClosed();}
            }
            Thread.sleep(100);try(var reopened=new TcpPeer(tcp)){reopened.heartbeat();}
            try(var oversized=new TcpPeer(tcp)) {
                var header=ByteBuffer.allocate(11).putInt(0x12345678).put((byte)2).put((byte)2).put((byte)1).putInt(Integer.MAX_VALUE).array();
                oversized.sendBytes(header);oversized.awaitClosed();
            }
            try(var legacy=new TcpPeer(tcp)) {
                legacy.sendBytes(ByteBuffer.allocate(11).putInt(0x12345678).put((byte)1).put((byte)1).put((byte)1).putInt(0).array());legacy.awaitClosed();
            }
            try(var slow=new Socket("127.0.0.1",web)) {
                slow.setSoTimeout(3000);slow.getOutputStream().write('G');Thread.sleep(600);slow.getOutputStream().write('E');
                assertEquals(-1,slow.getInputStream().read()); // Partial traffic does not extend the absolute upgrade deadline.
            }
            var listener=new Listener();var socket=http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+web+"/ws"),listener).join();
            socket.sendText("{\"password\":\"private-wire-test\"}",true).join();assertEquals("error",listener.receive().getString("type"));
            socket.sendBinary(ByteBuffer.wrap(new byte[]{(byte)0x80}),true).join();assertEquals("error",listener.receive().getString("type"));
            var event=new JSONObject();event.put("type","heartbeat");socket.sendBinary(ByteBuffer.wrap(ProtocolWire.encodeWeb(event,true)),true).join();assertEquals("heartbeat",listener.receive().getString("type"));
            socket.sendBinary(ByteBuffer.wrap(new byte[64*1024+1]),true).join();assertTrue(listener.closed.await(5,TimeUnit.SECONDS));
            assertFalse(server.output().contains("private-wire-test"));
        }
    }
    private static class Listener implements WebSocket.Listener {
        final BlockingQueue<JSONObject> inbox=new LinkedBlockingQueue<>();final CountDownLatch closed=new CountDownLatch(1);
        final java.io.ByteArrayOutputStream fragments=new java.io.ByteArrayOutputStream();
        @Override public void onOpen(WebSocket ws){ws.request(1);}
        @Override public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer data,boolean last){byte[] bytes=new byte[data.remaining()];data.get(bytes);fragments.writeBytes(bytes);if(last){inbox.add(ProtocolWire.decodeWeb(fragments.toByteArray(),false));fragments.reset();}ws.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason){closed.countDown();return null;}
        JSONObject receive() throws Exception {var result=inbox.poll(5,TimeUnit.SECONDS);assertNotNull(result);return result;}
    }
}
