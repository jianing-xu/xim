package cn.xjn.xim.server.handler;

import cn.xjn.xim.codec.PacketCodecHandler;
import cn.xjn.xim.codec.Spliter;
import cn.xjn.xim.handler.IMIdleStateHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * @author xjn
 * @date 2023-12-28
 */
public class IMServerChannelInitializer extends ChannelInitializer<NioSocketChannel> {

    @Override
    protected void initChannel(NioSocketChannel ch) throws Exception {
        ch.pipeline().addLast(new IMIdleStateHandler());
        ch.pipeline().addLast(new Spliter());
        ch.pipeline().addLast(PacketCodecHandler.INSTANCE);
        ch.pipeline().addLast(LoginRequestHandler.INSTANCE);
        ch.pipeline().addLast(HeartbeatRequestHandler.INSTANCE);
        ch.pipeline().addLast(AuthHandler.INSTANCE);
        ch.pipeline().addLast(IMServerHandler.INSTANCE);
    }
}
