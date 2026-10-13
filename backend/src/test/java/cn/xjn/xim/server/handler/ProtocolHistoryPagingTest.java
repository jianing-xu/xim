package cn.xjn.xim.server.handler;
import cn.xjn.xim.attribute.Attributes;
import cn.xjn.xim.protobuf.ProtocolWire;
import cn.xjn.xim.protocol.request.HistoryRequestPacket;
import cn.xjn.xim.storage.Storage;
import cn.xjn.xim.support.ChannelTestSupport;
import cn.xjn.xim.web.WebPacketEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ProtocolHistoryPagingTest extends ChannelTestSupport {
    @Test public void largeUnicodeHistoryFitsBoundedFramesAndBothDirectionsNeverSkipMessages() {
        var browser=channel(new WebPacketEncoder(),IMServerHandler.INSTANCE);browser.attr(Attributes.WEB).set(true);
        var peer=channel();var self=login(browser,"large-web-"+UUID.randomUUID());var other=login(peer,"large-peer-"+UUID.randomUUID());
        Set<String> expected=new HashSet<>();for(int i=0;i<55;i++) expected.add(Storage.get().save(other,self.getUserId(),null,"你".repeat(4000)).getId());
        for(boolean forward:List.of(false,true)) {
            Set<String> found=new HashSet<>();String cursor=forward?"0":null;boolean more=true;int pages=0;
            while(more) {
                var request=new HistoryRequestPacket();request.setKind("user");request.setId(other.getUserId());request.setLimit(50);
                if(forward)request.setAfter(cursor);else request.setBefore(cursor);
                browser.writeInbound(request);BinaryWebSocketFrame frame=browser.readOutbound();assertNotNull(frame);
                try {
                    assertTrue(frame.content().readableBytes()<=64*1024);
                    var response=ProtocolWire.decodeWeb(io.netty.buffer.ByteBufUtil.getBytes(frame.content()),false);
                    assertEquals("history",response.getString("type"));
                    for(Object value:response.getJSONArray("messages")) assertTrue(found.add(((com.alibaba.fastjson.JSONObject)value).getString("id")));
                    more=response.getBooleanValue("hasMore");cursor=response.getString(forward?"nextAfter":"nextBefore");pages++;
                } finally {frame.release();}
            }
            assertTrue(pages>2);assertEquals(expected,found);
        }
    }
}
