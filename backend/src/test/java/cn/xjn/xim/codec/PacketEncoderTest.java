package cn.xjn.xim.codec;

import cn.xjn.xim.protocol.request.MessageRequestPacket;
import cn.xjn.xim.support.ChannelTestSupport;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.junit.Assert.*;

public class PacketEncoderTest extends ChannelTestSupport {
    @Test
    public void encodesOutboundMessage() {
        EmbeddedChannel channel = channel(new PacketEncoder());
        MessageRequestPacket source = new MessageRequestPacket("user-2", "你好 🌍");
        assertTrue(channel.writeOutbound(source));
        ByteBuf buffer = channel.readOutbound();
        try {
            MessageRequestPacket decoded = (MessageRequestPacket) PacketCodec.INSTANCE.decode(buffer);
            assertEquals(source.getToUserId(), decoded.getToUserId());
            assertEquals(source.getMessage(), decoded.getMessage());
        } finally {
            buffer.release();
        }
    }
}
