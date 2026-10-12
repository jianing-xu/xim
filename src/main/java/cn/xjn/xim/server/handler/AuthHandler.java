package cn.xjn.xim.server.handler;

import cn.xjn.xim.util.SessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
@ChannelHandler.Sharable
public class AuthHandler extends ChannelInboundHandlerAdapter {

    public static final AuthHandler INSTANCE = new AuthHandler();

    private AuthHandler() {
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!SessionManager.hasLogin(ctx.channel())) {
            log.warn("Authenticate failed, force to close connection.");
            ReferenceCountUtil.release(msg);
            ctx.channel().close();
        } else {
            super.channelRead(ctx, msg);
        }
    }
}
