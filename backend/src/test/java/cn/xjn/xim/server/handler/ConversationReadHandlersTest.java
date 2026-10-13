package cn.xjn.xim.server.handler;

import cn.xjn.xim.codec.PacketCodec;
import cn.xjn.xim.protocol.request.ReadRequestPacket;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.storage.Storage;
import cn.xjn.xim.support.ChannelTestSupport;
import cn.xjn.xim.web.*;
import com.alibaba.fastjson.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ConversationReadHandlersTest extends ChannelTestSupport {
    @Test public void webReadingRequiresLoginAndConversationAccessAndReturnsCommittedSummary() {
        var browser=channel(new WebPacketEncoder(),new WebRequestHandler(),IMServerHandler.INSTANCE);
        JSONObject request=new JSONObject(); request.put("type","read"); request.put("kind","user"); request.put("id","missing"); request.put("sequence","1"); request.put("requestId","read-1");
        browser.writeInbound(new TextWebSocketFrame(request.toJSONString())); assertEquals("error",web(browser).getString("type"));
        var aliceChannel=channel(); var alice=login(aliceChannel,"read-a-"+UUID.randomUUID()); var bob=login(browser,"read-b-"+UUID.randomUUID());
        var saved=Storage.get().save(alice,bob.getUserId(),null,"已提交");
        request.put("id",alice.getUserId()); request.put("sequence",Long.toString(saved.getSequence()));
        browser.writeInbound(new TextWebSocketFrame(request.toJSONString())); var result=web(browser);
        assertEquals("read",result.getString("type")); assertEquals("read-1",result.getString("requestId"));
        var chat=result.getJSONObject("conversation"); assertEquals(saved.getSequence(),chat.getLongValue("readSequence"));
        assertEquals(0,chat.getLongValue("unread")); assertEquals(saved.getId(),chat.getJSONObject("lastMessage").getString("id"));
        assertTrue(result.getLongValue("syncCursor")>=saved.getSequence());
        request.put("sequence",Long.toString(Long.MAX_VALUE)); browser.writeInbound(new TextWebSocketFrame(request.toJSONString())); assertEquals("error",web(browser).getString("type"));
        String group=group(aliceChannel); request.put("kind","group"); request.put("id",group); request.put("sequence",Long.toString(saved.getSequence()));
        browser.writeInbound(new TextWebSocketFrame(request.toJSONString())); assertTrue(web(browser).getString("reason").contains("加入"));
        browser.writeInbound(new TextWebSocketFrame("{\"type\":\"history\",\"kind\":\"user\",\"id\":\""+alice.getUserId()+"\",\"after\":\"0\",\"limit\":1,\"requestId\":\"sync\"}"));
        var history=web(browser); assertEquals("history",history.getString("type")); assertEquals(Long.toString(saved.getSequence()),history.getString("nextAfter"));
        assertEquals(saved.getId(),history.getJSONArray("messages").getJSONObject(0).getString("id"));
    }
    @Test public void tcpCodecPreservesReadCorrelationAndServerEnforcesMonotonicPosition() {
        var sender=channel(IMServerHandler.INSTANCE); var peer=channel();
        var bob=login(sender,"read-tcp-b-"+UUID.randomUUID()); var alice=login(peer,"read-tcp-a-"+UUID.randomUUID());
        var first=Storage.get().save(alice,bob.getUserId(),null,"first"); var last=Storage.get().save(alice,bob.getUserId(),null,"last");
        var request=new ReadRequestPacket(); request.setKind("user"); request.setId(alice.getUserId()); request.setSequence(Long.toString(last.getSequence())); request.setRequestId("tcp-read");
        var buffer=Unpooled.buffer();
        try {
            PacketCodec.INSTANCE.encode(buffer,request); var decoded=(ReadRequestPacket)PacketCodec.INSTANCE.decode(buffer);
            assertEquals("tcp-read",decoded.getRequestId()); assertEquals(request.getSequence(),decoded.getSequence());
            sender.writeInbound(decoded); var response=(ReadResponsePacket)sender.readOutbound();
            assertEquals("tcp-read",response.getRequestId()); assertEquals(0,response.getConversation().getUnread());
            buffer.clear(); PacketCodec.INSTANCE.encode(buffer,response); var roundtrip=(ReadResponsePacket)PacketCodec.INSTANCE.decode(buffer);
            assertEquals(last.getSequence(),roundtrip.getConversation().getReadSequence());
            request.setSequence(Long.toString(first.getSequence())); sender.writeInbound(request);
            assertEquals(last.getSequence(),((ReadResponsePacket)sender.readOutbound()).getConversation().getReadSequence());
        } finally { buffer.release(); }
    }
    private JSONObject web(io.netty.channel.embedded.EmbeddedChannel channel) {
        TextWebSocketFrame frame=channel.readOutbound(); assertNotNull(frame);
        try { return JSON.parseObject(frame.text()); } finally { frame.release(); }
    }
}
