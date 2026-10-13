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
        ch.pipeline().addLast(cn.xjn.xim.server.limits.ConnectionGuard.INSTANCE);
        ch.pipeline().addLast(new IMIdleStateHandler());
        ch.pipeline().addLast(new Spliter());
        ch.pipeline().addLast(PacketCodecHandler.INSTANCE);
        ch.pipeline().addLast(new cn.xjn.xim.server.limits.RequestRateGuard());
        ch.pipeline().addLast(cn.xjn.xim.storage.Storage.EXECUTOR, LoginRequestHandler.INSTANCE);
        ch.pipeline().addLast(cn.xjn.xim.storage.Storage.EXECUTOR, AccountRequestHandler.INSTANCE);
        ch.pipeline().addLast(cn.xjn.xim.storage.Storage.EXECUTOR, HeartbeatRequestHandler.INSTANCE);
        ch.pipeline().addLast(cn.xjn.xim.storage.Storage.EXECUTOR, AuthHandler.INSTANCE);
        ch.pipeline().addLast(cn.xjn.xim.storage.Storage.EXECUTOR, IMServerHandler.INSTANCE);
    }
}
