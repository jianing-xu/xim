package cn.xjn.xim.auth;

import io.netty.channel.ChannelHandlerContext;
import java.net.InetSocketAddress;

public final class AuthAttempts {
    private AuthAttempts() {}
    private static String address(ChannelHandlerContext ctx) {
        return ctx.channel().remoteAddress() instanceof InetSocketAddress socket
                ? socket.getAddress().getHostAddress() : "local";
    }
    private static String userKey(String username) {
        return "login:user:" + (AccountPolicy.validUsername(username) ? username : "invalid");
    }
    public static boolean allowed(ChannelHandlerContext ctx, String username) {
        return AttemptLimiter.INSTANCE.allowed("login:ip:" + address(ctx), 20)
                && AttemptLimiter.INSTANCE.allowed(userKey(username), 5);
    }
    public static void failure(ChannelHandlerContext ctx, String username) {
        AttemptLimiter.INSTANCE.record("login:ip:" + address(ctx));
        AttemptLimiter.INSTANCE.record(userKey(username));
    }
    public static void success(String username) { AttemptLimiter.INSTANCE.clear(userKey(username)); }
    public static boolean registration(ChannelHandlerContext ctx) {
        String key = "register:ip:" + address(ctx);
        if (!AttemptLimiter.INSTANCE.allowed(key, 10)) return false;
        AttemptLimiter.INSTANCE.record(key);
        return true;
    }
}
