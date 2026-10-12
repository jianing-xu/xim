package cn.xjn.xim.client.handler;

import cn.xjn.xim.codec.PacketCodecHandler;
import cn.xjn.xim.codec.Spliter;
import cn.xjn.xim.handler.IMIdleStateHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

/**
 * @author xjn
 * @date 2023-12-28
 */
public class IMClientChannelInitializer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline().addLast(new IMIdleStateHandler());
        ch.pipeline().addLast(new Spliter());
        ch.pipeline().addLast(PacketCodecHandler.INSTANCE);
        ch.pipeline().addLast(new HeartbeatTimerHandler());
        ch.pipeline().addLast(IMClientHandler.INSTANCE);
    }
}
