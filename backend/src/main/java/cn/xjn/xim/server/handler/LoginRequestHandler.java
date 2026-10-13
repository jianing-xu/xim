package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.LoginRequestPacket;
import cn.xjn.xim.protocol.response.LoginResponsePacket;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import cn.xjn.xim.auth.AccountPolicy;
import cn.xjn.xim.auth.AuthAttempts;

/**
 * @author xjn
 * @date 2023-12-26
 */
@Slf4j
@ChannelHandler.Sharable
public class LoginRequestHandler extends SimpleChannelInboundHandler<LoginRequestPacket> {

    public static final LoginRequestHandler INSTANCE = new LoginRequestHandler();

    private LoginRequestHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, LoginRequestPacket requestPacket) throws Exception {
        LoginResponsePacket responsePacket = new LoginResponsePacket();
        String username = requestPacket.getUsername();
        responsePacket.setRequestId(requestPacket.getRequestId());
        try {
            boolean allowed = AuthAttempts.allowed(ctx, username);
            Session session = !SessionManager.hasLogin(ctx.channel()) && allowed
                    ? cn.xjn.xim.storage.Storage.get().authenticate(username, requestPacket.getPassword()) : null;
            if (session != null) {
                AuthAttempts.success(username);
                responsePacket.setUsername(session.getUsername());
                responsePacket.setSuccess(true);
                String userId = session.getUserId();
            responsePacket.setUserId(userId);
            if (Boolean.TRUE.equals(ctx.channel().attr(cn.xjn.xim.attribute.Attributes.WEB).get())) {
                var grant = cn.xjn.xim.storage.Storage.get().issueSession(userId);
                cn.xjn.xim.web.WebSessions.bind(ctx, grant.sessionId(), grant.expiresAt());
                responsePacket.setSessionTicket(grant.ticket());
                responsePacket.setExpiresAt(grant.expiresAt());
                responsePacket.setConnectionId(ctx.channel().attr(cn.xjn.xim.attribute.Attributes.CONNECTION_ID).get());
            } else cn.xjn.xim.storage.Storage.get().revokeSessions(userId);
            SessionManager.bindSession(session, ctx.channel());
                cn.xjn.xim.storage.Storage.get().conversations(session.getUserId()).stream()
                        .filter(chat -> chat.getKind().equals("group"))
                        .forEach(chat -> SessionManager.getChannelGroup(chat.getId()).add(ctx.channel()));
                log.info("[{}] login successful.", session.getUsername());
            } else {
                if (allowed && !SessionManager.hasLogin(ctx.channel())) AuthAttempts.failure(ctx, username);
                responsePacket.setSuccess(false);
                responsePacket.setReason(allowed ? AccountPolicy.INVALID_CREDENTIALS : AccountPolicy.LIMITED);
                log.info("login failed.");
            }
        } catch (RuntimeException failure) {
            SessionManager.unbindSession(ctx.channel());
            responsePacket.setSuccess(false);
            responsePacket.setUserId(null);
            responsePacket.setSessionTicket(null);
            responsePacket.setReason("账号操作失败，请稍后重试");
            log.warn("Account login operation failed");
        } finally { requestPacket.setPassword(null); }
        ctx.channel().writeAndFlush(responsePacket);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        SessionManager.unbindSession(ctx.channel());
        super.channelInactive(ctx);
    }
}
