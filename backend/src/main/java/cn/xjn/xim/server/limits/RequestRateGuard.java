package cn.xjn.xim.server.limits;

import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import java.util.function.LongSupplier;

/** Per-connection token bucket before the shared, bounded storage executor. */
public final class RequestRateGuard extends ChannelInboundHandlerAdapter {
    private final double rate;
    private final double burst;
    private final LongSupplier clock;
    private double tokens;
    private long previous;
    public RequestRateGuard() { this(Integer.getInteger("xim.requests.perSecond", 30), 60, System::nanoTime); }
    public RequestRateGuard(int rate, int burst, LongSupplier clock) {
        if (rate < 1 || rate > 10_000 || burst < 1) throw new IllegalArgumentException("Invalid request limit");
        this.rate = rate; this.burst = burst; this.tokens = burst; this.clock = clock; this.previous = clock.getAsLong();
    }
    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        long now = clock.getAsLong();
        tokens = Math.min(burst, tokens + Math.max(0, now - previous) * rate / 1_000_000_000d); previous = now;
        if (tokens < 1) { ReferenceCountUtil.release(message); ctx.close(); return; }
        tokens--; ctx.fireChannelRead(message);
    }
}
