package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.MessageRequestPacket;
import cn.xjn.xim.protocol.response.MessageResponsePacket;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.support.ChannelTestSupport;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.*;

public class MessageRequestHandlerTest extends ChannelTestSupport {
    @Test
    public void routesPrivateMessageWithSenderIdentity() {
        EmbeddedChannel sender = channel(IMServerHandler.INSTANCE);
        EmbeddedChannel receiver = channel();
        EmbeddedChannel outsider = channel();
        Session from = login(sender, "Alice");
        Session to = login(receiver, "Bob");
        login(outsider, "Eve");
        sender.writeInbound(new MessageRequestPacket(to.getUserId(), "你好 🌍"));
        MessageResponsePacket response = receiver.readOutbound();
        assertEquals(from.getUserId(), response.getFromUserId());
        assertEquals(from.getUsername(), response.getFromUsername());
        assertEquals("你好 🌍", response.getMessage());
        assertNull(sender.readOutbound());
        assertNull(outsider.readOutbound());
    }

    @Test
    public void ignoresOfflineRecipientWithoutClosingSender() {
        EmbeddedChannel sender = channel(MessageRequestHandler.INSTANCE);
        login(sender, "Alice");
        assertFalse(sender.writeInbound(new MessageRequestPacket("missing", "hello")));
        assertTrue(sender.isActive());
        assertNull(sender.readOutbound());
    }
}
