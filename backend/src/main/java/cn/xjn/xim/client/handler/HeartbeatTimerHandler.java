package cn.xjn.xim.client.handler;

import cn.xjn.xim.protocol.request.HeartbeatRequestPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

/**
 * @author xjn
 * @date 2023-12-28
 */
public class HeartbeatTimerHandler extends ChannelInboundHandlerAdapter {

    private static final int HEARTBEAT_INTERVAL = 5;
    private ScheduledFuture<?> heartbeatTask;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        scheduleHeartbeat(ctx);
        super.channelActive(ctx);
    }

    private void scheduleHeartbeat(ChannelHandlerContext ctx) {
        heartbeatTask = ctx.executor().schedule(() -> {
            if (!ctx.channel().isActive()) {
                return;
            }
            HeartbeatRequestPacket requestPacket = new HeartbeatRequestPacket();
            ctx.writeAndFlush(requestPacket);
            scheduleHeartbeat(ctx);
        }, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelHeartbeat();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cancelHeartbeat();
        super.handlerRemoved(ctx);
    }

    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }
}
