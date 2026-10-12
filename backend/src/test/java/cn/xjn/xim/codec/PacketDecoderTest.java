package cn.xjn.xim.codec;

import cn.xjn.xim.protocol.request.MessageRequestPacket;
import cn.xjn.xim.support.ChannelTestSupport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.Test;

import static org.junit.Assert.*;

public class PacketDecoderTest extends ChannelTestSupport {
    @Test
    public void decodesFragmentedPacketsOneByteAtATime() {
        EmbeddedChannel channel = channel(new PacketDecoder());
        ByteBuf buffer = Unpooled.buffer();
        PacketCodec.INSTANCE.encode(buffer, new MessageRequestPacket("user-2", "你好 🌍"));
        try {
            while (buffer.readableBytes() > 1) {
                assertFalse(channel.writeInbound(buffer.readRetainedSlice(1)));
            }
            assertTrue(channel.writeInbound(buffer.readRetainedSlice(1)));
            MessageRequestPacket decoded = channel.readInbound();
            assertEquals("user-2", decoded.getToUserId());
            assertEquals("你好 🌍", decoded.getMessage());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void skipsUnknownCommandAndContinuesToNextPacket() {
        EmbeddedChannel channel = channel(new PacketDecoder());
        ByteBuf buffer = Unpooled.buffer();
        PacketCodec.INSTANCE.encode(buffer, new MessageRequestPacket("ignored", "ignored"));
        buffer.setByte(6, 127);
        PacketCodec.INSTANCE.encode(buffer, new MessageRequestPacket("user-2", "hello"));
        assertTrue(channel.writeInbound(buffer));
        MessageRequestPacket decoded = channel.readInbound();
        assertEquals("user-2", decoded.getToUserId());
        assertNull(channel.readInbound());
    }

    @Test
    public void rejectsNegativeLength() {
        EmbeddedChannel channel = channel(new PacketDecoder());
        ByteBuf buffer = Unpooled.buffer();
        PacketCodec.INSTANCE.encode(buffer, new MessageRequestPacket("user-2", "hello"));
        buffer.setInt(7, -1);
        assertThrows(DecoderException.class, () -> channel.writeInbound(buffer));
        // Discard the malformed cumulation before closing the decoder.
        channel.pipeline().remove(PacketDecoder.class);
    }
}
