package cn.xjn.xim.codec;

import cn.xjn.xim.protocol.request.LoginRequestPacket;
import cn.xjn.xim.support.ChannelTestSupport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.*;

public class SpliterTest extends ChannelTestSupport {
    @Test
    public void waitsForEveryPartialHeaderAndBody() {
        for (int split = 1; split <= PacketCodec.HEADER_LENGTH + 1; split++) {
            EmbeddedChannel channel = channel(new Spliter(), PacketCodecHandler.INSTANCE);
            ByteBuf buffer = packet("用户");
            assertFalse(channel.writeInbound(buffer.readRetainedSlice(split)));
            assertTrue(channel.isActive());
            assertTrue(channel.writeInbound(buffer));
            LoginRequestPacket decoded = channel.readInbound();
            assertEquals("用户", decoded.getUsername());
            assertNull(channel.readInbound());
        }
    }

    @Test
    public void decodesCoalescedPacketsInOrder() {
        EmbeddedChannel channel = channel(new Spliter(), PacketCodecHandler.INSTANCE);
        ByteBuf buffer = packet("first");
        ByteBuf second = packet("second");
        try {
            buffer.writeBytes(second);
        } finally {
            second.release();
        }
        assertTrue(channel.writeInbound(buffer));
        assertEquals("first", ((LoginRequestPacket) channel.readInbound()).getUsername());
        assertEquals("second", ((LoginRequestPacket) channel.readInbound()).getUsername());
        assertNull(channel.readInbound());
    }

    @Test
    public void closesConnectionWithInvalidMagicNumber() {
        EmbeddedChannel channel = channel(new Spliter());
        assertFalse(channel.writeInbound(Unpooled.buffer().writeInt(0)));
        assertFalse(channel.isActive());
        assertNull(channel.readInbound());
    }

    private ByteBuf packet(String username) {
        LoginRequestPacket packet = new LoginRequestPacket();
        packet.setUsername(username);
        packet.setPassword("password");
        ByteBuf buffer = Unpooled.buffer();
        PacketCodec.INSTANCE.encode(buffer, packet);
        return buffer;
    }
}
