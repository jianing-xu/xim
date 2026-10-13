package cn.xjn.xim.server.limits;
import io.netty.channel.*;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
/** Absolute upgrade deadline, independent of slow partial-header reads. */
public final class HandshakeDeadline extends ChannelInboundHandlerAdapter {
    private static final AttributeKey<ScheduledFuture<?>> DEADLINE=AttributeKey.valueOf("xim.handshake.deadline");
    @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
        int seconds=Integer.getInteger("xim.handshake.timeoutSeconds",15);
        if(seconds<1||seconds>60) throw new IllegalArgumentException("Invalid handshake deadline");
        ctx.channel().attr(DEADLINE).set(ctx.executor().schedule(()->ctx.close(),seconds,TimeUnit.SECONDS));
        super.channelActive(ctx);
    }
    public static void complete(Channel channel) { var task=channel.attr(DEADLINE).getAndSet(null);if(task!=null)task.cancel(false); }
    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {complete(ctx.channel());super.channelInactive(ctx);}
}
