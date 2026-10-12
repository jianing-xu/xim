package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.LogoutRequestPacket;
import cn.xjn.xim.protocol.request.MessageRequestPacket;
import cn.xjn.xim.protocol.response.LogoutResponsePacket;
import cn.xjn.xim.support.ChannelTestSupport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.*;

public class AuthHandlerTest extends ChannelTestSupport {
    @Test
    public void closesUnauthenticatedChannelAndReleasesRejectedMessage() {
        EmbeddedChannel channel = channel(AuthHandler.INSTANCE);
        ByteBuf buffer = Unpooled.buffer().writeByte(1);
        assertFalse(channel.writeInbound(buffer));
        assertFalse(channel.isActive());
        assertEquals(0, buffer.refCnt());
        assertNull(channel.readInbound());
    }

    @Test
    public void forwardsAuthenticatedMessages() {
        EmbeddedChannel channel = channel(AuthHandler.INSTANCE);
        login(channel, "Alice");
        MessageRequestPacket packet = new MessageRequestPacket("Bob", "hello");
        assertTrue(channel.writeInbound(packet));
        assertSame(packet, channel.readInbound());
        assertTrue(channel.isActive());
    }

    @Test
    public void rechecksAuthenticationAfterLogout() {
        EmbeddedChannel channel = channel(AuthHandler.INSTANCE, IMServerHandler.INSTANCE);
        login(channel, "Alice");
        assertFalse(channel.writeInbound(new LogoutRequestPacket()));
        assertTrue(((LogoutResponsePacket) channel.readOutbound()).isSuccess());
        assertFalse(channel.writeInbound(new MessageRequestPacket("Bob", "hello")));
        assertFalse(channel.isActive());
        assertNull(channel.readOutbound());
    }
}
