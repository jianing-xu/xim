package cn.xjn.xim.codec;

import cn.xjn.xim.protocol.Packet;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;

import java.util.List;

/**
 * @author xjn
 * @date 2023-12-26
 */
@ChannelHandler.Sharable
public class PacketCodecHandler extends MessageToMessageCodec<ByteBuf, Packet> {

    public static final PacketCodecHandler INSTANCE = new PacketCodecHandler();

    private PacketCodecHandler() {
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Packet packet, List<Object> out) throws Exception {
        ByteBuf byteBuf = ctx.channel().alloc().ioBuffer();
        try { PacketCodec.INSTANCE.encode(byteBuf, packet); out.add(byteBuf); }
        catch (RuntimeException failure) { byteBuf.release(); throw failure; }
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf byteBuf, List<Object> out) throws Exception {
        Packet packet = PacketCodec.INSTANCE.decode(byteBuf);
        if (packet != null) {
            out.add(packet);
        } else ctx.close();
    }
}
