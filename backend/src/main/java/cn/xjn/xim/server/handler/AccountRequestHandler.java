package cn.xjn.xim.server.handler;

import cn.xjn.xim.auth.AccountPolicy;
import cn.xjn.xim.auth.AuthAttempts;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.request.RegisterRequestPacket;
import cn.xjn.xim.protocol.request.ChangePasswordRequestPacket;
import cn.xjn.xim.protocol.response.RegisterResponsePacket;
import cn.xjn.xim.protocol.response.ChangePasswordResponsePacket;
import cn.xjn.xim.storage.Storage;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.*;

/** Both transports run this handler on the ordered storage executor, before the login guard. */
@ChannelHandler.Sharable
public final class AccountRequestHandler extends SimpleChannelInboundHandler<Packet> {
    public static final AccountRequestHandler INSTANCE = new AccountRequestHandler();
    private AccountRequestHandler() {}
    @Override protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
        if (packet instanceof RegisterRequestPacket request) register(ctx, request);
        else if (packet instanceof ChangePasswordRequestPacket request) change(ctx, request);
        else ctx.fireChannelRead(packet);
    }
    private void register(ChannelHandlerContext ctx, RegisterRequestPacket request) {
        var response = new RegisterResponsePacket(); response.setRequestId(request.getRequestId());
        try {
            if (SessionManager.hasLogin(ctx.channel())) throw new IllegalArgumentException("请先退出当前账号");
            if (!AuthAttempts.registration(ctx)) throw new IllegalArgumentException(AccountPolicy.LIMITED);
            Storage.get().register(request.getUsername(), request.getPassword(), request.getDisplayName());
            response.setSuccess(true);
        } catch (RuntimeException failure) { response.setReason(reason(failure)); }
        finally { request.setPassword(null); }
        ctx.writeAndFlush(response);
    }
    private void change(ChannelHandlerContext ctx, ChangePasswordRequestPacket request) {
        var response = new ChangePasswordResponsePacket(); response.setRequestId(request.getRequestId());
        String userId = null;
        try {
            var session = SessionManager.getSession(ctx.channel());
            if (session == null) throw new IllegalArgumentException("请先登录");
            if (!AuthAttempts.allowed(ctx, session.getUsername())) throw new IllegalArgumentException(AccountPolicy.LIMITED);
            try { Storage.get().changePassword(session.getUserId(), request.getCurrentPassword(), request.getNewPassword()); }
            catch (IllegalArgumentException failure) {
                if (AccountPolicy.INVALID_CREDENTIALS.equals(failure.getMessage())) AuthAttempts.failure(ctx, session.getUsername());
                throw failure;
            }
            AuthAttempts.success(session.getUsername());
            userId = session.getUserId();
            response.setSuccess(true);
            // Immediately remove authorization, before any already-queued request can execute.
            SessionManager.unbindSession(ctx.channel());
            SessionManager.revokeUserSessions(userId);
        } catch (RuntimeException failure) { response.setReason(reason(failure)); }
        finally { request.setCurrentPassword(null); request.setNewPassword(null); }
        final String revokedUser = userId;
        ctx.writeAndFlush(response).addListener(future -> {
            if (revokedUser != null) {
                ctx.close();
            }
        });
    }
    private String reason(RuntimeException failure) {
        return failure instanceof IllegalArgumentException ? failure.getMessage() : "账号操作失败，请稍后重试";
    }
}
