package cn.xjn.xim.codec;

import cn.xjn.xim.protocol.Packet;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * @author xjn
 * @date 2023-12-26
 */
public class PacketDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < PacketCodec.HEADER_LENGTH) {
            return;
        }
        int length = in.getInt(in.readerIndex() + 7);
        if (length >= 0 && in.readableBytes() - PacketCodec.HEADER_LENGTH < length) {
            return;
        }
        Packet packet = PacketCodec.INSTANCE.decode(in);
        if (packet != null) {
            out.add(packet);
        }
    }
}
