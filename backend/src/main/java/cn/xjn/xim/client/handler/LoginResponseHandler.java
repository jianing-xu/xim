package cn.xjn.xim.client.handler;

import cn.xjn.xim.protocol.response.LoginResponsePacket;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * @author xjn
 * @date 2023-12-26
 */
@Slf4j
public class LoginResponseHandler extends SimpleChannelInboundHandler<LoginResponsePacket> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, LoginResponsePacket responsePacket) throws Exception {
        if (responsePacket.isSuccess()) {
            String userId = responsePacket.getUserId();
            String username = responsePacket.getUsername();
            SessionManager.bindSession(new Session(userId, username), ctx.channel());
            log.info("[{}:{}] login successful.", responsePacket.getUserId(), responsePacket.getUsername());
        } else {
            log.warn("[{}] login failed, the reason is: {}.", responsePacket.getUsername(), responsePacket.getReason());
        }
    }
}
