package cn.xjn.xim.client.handler;

import cn.xjn.xim.protocol.request.HeartbeatRequestPacket;
import cn.xjn.xim.support.ChannelTestSupport;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class HeartbeatTimerHandlerTest extends ChannelTestSupport {
    @Test
    public void sendsPeriodicHeartbeatAfterFiveSeconds() {
        EmbeddedChannel channel = channel(new HeartbeatTimerHandler());
        channel.freezeTime();
        assertNull(channel.readOutbound());
        channel.advanceTimeBy(4, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertNull(channel.readOutbound());
        channel.advanceTimeBy(1, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertTrue(channel.readOutbound() instanceof HeartbeatRequestPacket);
        channel.advanceTimeBy(5, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertTrue(channel.readOutbound() instanceof HeartbeatRequestPacket);
        assertNull(channel.readOutbound());
    }

    @Test
    public void cancelsHeartbeatWhenDisconnected() {
        EmbeddedChannel channel = channel(new HeartbeatTimerHandler());
        channel.freezeTime();
        channel.close();
        channel.advanceTimeBy(30, TimeUnit.SECONDS);
        assertEquals(-1, channel.runScheduledPendingTasks());
        assertNull(channel.readOutbound());
    }

    @Test
    public void cancelsHeartbeatWhenHandlerRemoved() {
        HeartbeatTimerHandler handler = new HeartbeatTimerHandler();
        EmbeddedChannel channel = channel(handler);
        channel.freezeTime();
        channel.pipeline().remove(handler);
        channel.advanceTimeBy(30, TimeUnit.SECONDS);
        assertEquals(-1, channel.runScheduledPendingTasks());
        assertNull(channel.readOutbound());
    }
}
