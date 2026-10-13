package cn.xjn.xim.server.limits;

import io.netty.channel.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts accepted TCP and HTTP sockets together, including pre-handshake sockets. */
@ChannelHandler.Sharable
public final class ConnectionGuard extends ChannelInboundHandlerAdapter {
    public static final ConnectionGuard INSTANCE = new ConnectionGuard(Integer.getInteger("xim.connections.max", 512));
    private final AtomicInteger active = new AtomicInteger();
    private final int max;
    public ConnectionGuard(int max) {
        if (max < 1 || max > 100_000) throw new IllegalArgumentException("Invalid connection limit");
        this.max = max;
    }
    @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (active.incrementAndGet() > max) { active.decrementAndGet(); ctx.close(); return; }
        ctx.channel().closeFuture().addListener(ignored -> active.decrementAndGet());
        super.channelActive(ctx);
    }
}
