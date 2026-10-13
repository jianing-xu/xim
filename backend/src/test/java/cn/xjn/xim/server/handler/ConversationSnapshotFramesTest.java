package cn.xjn.xim.server.handler;
import cn.xjn.xim.attribute.Attributes;
import cn.xjn.xim.protobuf.ProtocolWire;
import cn.xjn.xim.protocol.request.ConversationsRequestPacket;
import cn.xjn.xim.storage.Storage;
import cn.xjn.xim.support.ChannelTestSupport;
import cn.xjn.xim.web.WebPacketEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ConversationSnapshotFramesTest extends ChannelTestSupport {
    @Test public void fullDirectoryIsSplitIntoBoundedConsistentFramesAndHistoryKeepsTheBody() {
        var browser=channel(new WebPacketEncoder(),IMServerHandler.INSTANCE);browser.attr(Attributes.WEB).set(true);
        var self=login(browser,"snapshot-self-"+UUID.randomUUID());Set<String> expected=new HashSet<>();String body="中".repeat(4000);
        for(int i=0;i<180;i++) {
            var other=Storage.get().user("snapshot-other-"+UUID.randomUUID());expected.add(other.getUserId());Storage.get().save(other,self.getUserId(),null,body);
        }
        browser.writeInbound(new ConversationsRequestPacket());Set<String> found=new HashSet<>();String snapshot=null;String cursor=null;int part=0;boolean more=true;
        while(more) {
            BinaryWebSocketFrame frame=browser.readOutbound();assertNotNull(frame);
            try {
                assertTrue(frame.content().readableBytes()<=64*1024);var event=ProtocolWire.decodeWeb(io.netty.buffer.ByteBufUtil.getBytes(frame.content()),false);
                if(part==0){snapshot=event.getString("snapshotId");cursor=event.getString("syncCursor");}
                assertEquals(snapshot,event.getString("snapshotId"));assertEquals(cursor,event.getString("syncCursor"));assertEquals(part++,event.getIntValue("part"));
                for(Object value:event.getJSONArray("conversations")) {
                    var chat=(com.alibaba.fastjson.JSONObject)value;assertTrue(found.add(chat.getString("id")));assertEquals(1,chat.getLongValue("unread"));assertEquals(0,chat.getLongValue("readSequence"));
                    assertTrue(chat.getJSONObject("lastMessage").getString("message").length()<250);
                }
                more=event.getBooleanValue("hasMore");
            } finally {frame.release();}
        }
        assertTrue(part>1);assertEquals(expected,found);assertNull(browser.readOutbound());
        assertEquals(body,Storage.get().history(self.getUserId(),"user",found.iterator().next(),null,50).messages().getFirst().getMessage());
    }
}
