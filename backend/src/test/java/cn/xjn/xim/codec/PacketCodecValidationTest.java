package cn.xjn.xim.codec;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.request.HeartbeatRequestPacket;
import cn.xjn.xim.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.Test;

import static org.junit.Assert.*;

public class PacketCodecValidationTest {
    @Test public void malformedCredentialsDoNotLeakIntoParserExceptions() {
        byte[] payload = "{\"password\":\"private-secret\", broken".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuf buffer = header(PacketCodec.MAGIC_NUMBER, payload.length);
        buffer.setByte(6, cn.xjn.xim.protocol.command.Command.LOGIN_REQUEST);
        buffer.writeBytes(payload);
        try {
            var failure = assertThrows(CorruptedFrameException.class, () -> PacketCodec.INSTANCE.decode(buffer));
            assertEquals("Invalid packet payload", failure.getMessage()); assertNull(failure.getCause());
        } finally { buffer.release(); }
    }
    @Test
    public void rejectsIncompleteHeader() {
        ByteBuf buffer = Unpooled.buffer().writeInt(PacketCodec.MAGIC_NUMBER);
        try {
            assertThrows(CorruptedFrameException.class, () -> PacketCodec.INSTANCE.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    public void rejectsInvalidMagicAndLengths() {
        for (int length : new int[]{-1, Integer.MAX_VALUE}) {
            ByteBuf buffer = header(PacketCodec.MAGIC_NUMBER, length);
            try {
                assertThrows(CorruptedFrameException.class, () -> PacketCodec.INSTANCE.decode(buffer));
            } finally {
                buffer.release();
            }
        }
        ByteBuf buffer = header(0, 0);
        try {
            assertThrows(CorruptedFrameException.class, () -> PacketCodec.INSTANCE.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    public void consumesUnsupportedCommandAndSerializerWithoutProducingPacket() {
        for (int offset : new int[]{5, 6}) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                PacketCodec.INSTANCE.encode(buffer, new HeartbeatRequestPacket());
                buffer.setByte(offset, 127);
                assertNull(PacketCodec.INSTANCE.decode(buffer));
                assertFalse(buffer.isReadable());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    public void usesVersionFromWireHeader() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            PacketCodec.INSTANCE.encode(buffer, new HeartbeatRequestPacket());
            buffer.setByte(4, 2);
            Packet packet = PacketCodec.INSTANCE.decode(buffer);
            assertEquals(Byte.valueOf((byte) 2), packet.getVersion());
        } finally {
            buffer.release();
        }
    }

    private ByteBuf header(int magic, int length) {
        return Unpooled.buffer().writeInt(magic).writeByte(1)
                .writeByte(Serializer.DEFAULT.getSerializerAlgorithm())
                .writeByte(new HeartbeatRequestPacket().getCommand()).writeInt(length);
    }
}
