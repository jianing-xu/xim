package cn.xjn.xim.client.handler;

import cn.xjn.xim.protocol.response.LogoutResponsePacket;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * @author xjn
 * @date 2023-12-26
 */
@Slf4j
public class LogoutResponseHandler extends SimpleChannelInboundHandler<LogoutResponsePacket> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, LogoutResponsePacket responsePacket) throws Exception {
        if (responsePacket.isSuccess()) {
            SessionManager.unbindSession(ctx.channel());
        }
    }
}
