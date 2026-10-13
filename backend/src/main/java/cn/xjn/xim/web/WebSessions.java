package cn.xjn.xim.web;

import cn.xjn.xim.attribute.Attributes;
import cn.xjn.xim.auth.SessionTokens;
import cn.xjn.xim.storage.Storage;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import java.util.concurrent.TimeUnit;

public final class WebSessions {
    public static boolean secureCookie() { return Boolean.parseBoolean(System.getProperty("xim.web.cookieSecure", "true")); }
    public static String cookieName() { return secureCookie() ? "__Host-xim_session" : "xim_session_dev"; }
    public static String cookieToken(String header) {
        if (header == null) return null;
        return ServerCookieDecoder.STRICT.decode(header).stream().filter(cookie -> cookie.name().equals(cookieName()))
                .map(io.netty.handler.codec.http.cookie.Cookie::value).findFirst().orElse(null);
    }
    public static void bind(ChannelHandlerContext ctx, String sessionId, long expiresAt) {
        var old = ctx.channel().attr(Attributes.SESSION_EXPIRY).getAndSet(null);
        if (old != null) old.cancel(false);
        ctx.channel().attr(Attributes.SESSION_ID).set(sessionId);
        ctx.channel().attr(Attributes.CONNECTION_ID).set(SessionTokens.random());
        // Absolute expiry also disconnects an otherwise idle, authorized socket.
        var task = ctx.executor().schedule(() -> {
            if (ctx.channel().isActive() && sessionId.equals(ctx.channel().attr(Attributes.SESSION_ID).get()))
                SessionManager.invalidate(ctx.channel(), "expired", "登录凭证已过期，请重新登录");
        }, Math.max(0,expiresAt-System.currentTimeMillis()),TimeUnit.MILLISECONDS);
        ctx.channel().attr(Attributes.SESSION_EXPIRY).set(task);
    }
    public static boolean validate(ChannelHandlerContext ctx) {
        String id = ctx.channel().attr(Attributes.SESSION_ID).get();
        if (SessionManager.hasLogin(ctx.channel()) && id != null && Storage.get().sessionCredential(id) == null) {
            SessionManager.invalidate(ctx.channel(), "revoked", "登录凭证已失效（过期或已撤销），请重新登录");
            return false;
        }
        return true;
    }
    private WebSessions() {}
}
