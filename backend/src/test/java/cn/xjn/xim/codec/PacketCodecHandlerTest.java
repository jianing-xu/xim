package cn.xjn.xim.codec;

import cn.xjn.xim.protocol.request.LoginRequestPacket;
import cn.xjn.xim.support.ChannelTestSupport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.*;

public class PacketCodecHandlerTest extends ChannelTestSupport {
    @Test
    public void encodesAndDecodesAcrossIndependentChannels() {
        EmbeddedChannel sender = channel(PacketCodecHandler.INSTANCE);
        EmbeddedChannel receiver = channel(PacketCodecHandler.INSTANCE);
        LoginRequestPacket source = new LoginRequestPacket();
        source.setUsername("root");
        source.setPassword("root");
        assertTrue(sender.writeOutbound(source));
        ByteBuf buffer = sender.readOutbound();
        assertTrue(receiver.writeInbound(buffer));
        LoginRequestPacket decoded = receiver.readInbound();
        assertEquals(source.getUsername(), decoded.getUsername());
        assertEquals(source.getPassword(), decoded.getPassword());
        assertEquals(0, buffer.refCnt());
    }

    @Test
    public void dropsUnsupportedPacketsAndReleasesBuffer() {
        EmbeddedChannel channel = channel(PacketCodecHandler.INSTANCE);
        ByteBuf buffer = Unpooled.buffer();
        PacketCodec.INSTANCE.encode(buffer, new LoginRequestPacket());
        buffer.setByte(5, 127);
        assertFalse(channel.writeInbound(buffer));
        assertNull(channel.readInbound());
        assertEquals(0, buffer.refCnt());
    }
}
