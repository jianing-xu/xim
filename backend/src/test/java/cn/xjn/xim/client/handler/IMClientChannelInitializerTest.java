package cn.xjn.xim.client.handler;

import cn.xjn.xim.codec.PacketCodecHandler;
import cn.xjn.xim.codec.Spliter;
import cn.xjn.xim.handler.IMIdleStateHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class IMClientChannelInitializerTest {
    @Test
    public void installsCodecHeartbeatAndSharedClientHandlerOnMultipleConnections() throws Exception {
        NioSocketChannel first = new NioSocketChannel();
        NioSocketChannel second = new NioSocketChannel();
        try {
            IMClientChannelInitializer initializer = new IMClientChannelInitializer();
            initializer.initChannel(first);
            initializer.initChannel(second);
            assertEquals(List.of(IMIdleStateHandler.class, Spliter.class, PacketCodecHandler.class,
                    HeartbeatTimerHandler.class, IMClientHandler.class),
                    first.pipeline().toMap().values().stream().map(Object::getClass).toList());
            assertSame(IMClientHandler.INSTANCE, second.pipeline().get(IMClientHandler.class));
            assertNotSame(first.pipeline().get(HeartbeatTimerHandler.class),
                    second.pipeline().get(HeartbeatTimerHandler.class));
        } finally {
            first.unsafe().closeForcibly();
            second.unsafe().closeForcibly();
        }
    }
}
