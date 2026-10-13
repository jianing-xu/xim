package cn.xjn.xim.server.handler;

import cn.xjn.xim.codec.PacketCodecHandler;
import cn.xjn.xim.codec.Spliter;
import cn.xjn.xim.handler.IMIdleStateHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class IMServerChannelInitializerTest {
    @Test
    public void installsFramingAuthenticationAndRequestHandlersInOrder() throws Exception {
        NioSocketChannel channel = new NioSocketChannel();
        try {
            new IMServerChannelInitializer().initChannel(channel);
            assertEquals(List.of(cn.xjn.xim.server.limits.ConnectionGuard.class, IMIdleStateHandler.class, Spliter.class, PacketCodecHandler.class, cn.xjn.xim.server.limits.RequestRateGuard.class,
                    LoginRequestHandler.class, AccountRequestHandler.class, HeartbeatRequestHandler.class, AuthHandler.class,
                    IMServerHandler.class),
                    channel.pipeline().toMap().values().stream().map(Object::getClass).toList());
        } finally {
            channel.unsafe().closeForcibly();
        }
    }
}
