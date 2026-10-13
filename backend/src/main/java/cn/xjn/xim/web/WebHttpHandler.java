package cn.xjn.xim.web;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Backend HTTP API and WebSocket origin validation. Frontend assets are hosted separately. */
public class WebHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final Set<String> allowedOrigins;

    public WebHttpHandler() {
        this(System.getProperty("xim.web.allowedOrigins", "http://localhost:5173,http://127.0.0.1:5173"));
    }

    public WebHttpHandler(String origins) {
        allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = new QueryStringDecoder(request.uri()).path();
        if (!request.decoderResult().isSuccess()) {
            respond(ctx, request, HttpResponseStatus.BAD_REQUEST, "text/plain; charset=utf-8", "Bad request".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.equals("/api/session")) {
            String origin = request.headers().get(HttpHeaderNames.ORIGIN);
            String host = request.headers().get(HttpHeaderNames.HOST);
            if (origin != null && !(origin.equals("http://"+host) || origin.equals("https://"+host) || allowedOrigins.contains(origin))
                    || "cross-site".equals(request.headers().get("Sec-Fetch-Site"))) {
                respond(ctx,request,HttpResponseStatus.FORBIDDEN,"text/plain",new byte[0]); return;
            }
            if (request.method().equals(HttpMethod.OPTIONS)) {
                String method = request.headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
                String headers = request.headers().get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS);
                boolean valid = origin != null && ("POST".equals(method) || "DELETE".equals(method))
                        && (headers == null || Arrays.stream(headers.split(",")).allMatch(header -> "content-type".equalsIgnoreCase(header.trim())));
                respond(ctx,request,valid?HttpResponseStatus.NO_CONTENT:HttpResponseStatus.FORBIDDEN,"text/plain",new byte[0]); return;
            }
            try {
                String cookie = null;
                if (request.method().equals(HttpMethod.POST)) {
                    if (!"application/json".equals(request.headers().get(HttpHeaderNames.CONTENT_TYPE))) {
                        respond(ctx,request,HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,"text/plain",new byte[0]); return;
                    }
                    var json = com.alibaba.fastjson.JSON.parseObject(request.content().toString(StandardCharsets.UTF_8),com.alibaba.fastjson.parser.Feature.DisableSpecialKeyDetect);
                    var saved = cn.xjn.xim.storage.Storage.get().redeemTicket(json == null ? null : json.getString("ticket"));
                    if (saved == null) { respond(ctx,request,HttpResponseStatus.UNAUTHORIZED,"text/plain",new byte[0]); return; }
                    long age = Math.max(1,(saved.expiresAt()-System.currentTimeMillis())/1000);
                    cookie = cookieHeader(saved.token(),age);
                } else if (request.method().equals(HttpMethod.DELETE)) {
                    var credential = cn.xjn.xim.storage.Storage.get().resumeSession(WebSessions.cookieToken(request.headers().get(HttpHeaderNames.COOKIE)));
                    if (credential != null) {
                        cn.xjn.xim.storage.Storage.get().revokeSession(credential.sessionId());
                        var channel = cn.xjn.xim.util.SessionManager.getChannel(credential.user().getUserId());
                        if (channel != null && credential.sessionId().equals(channel.attr(cn.xjn.xim.attribute.Attributes.SESSION_ID).get()))
                            cn.xjn.xim.util.SessionManager.invalidate(channel,"revoked","登录已注销，请重新登录");
                    }
                    cookie = cookieHeader("",0);
                } else { respond(ctx,request,HttpResponseStatus.METHOD_NOT_ALLOWED,"text/plain",new byte[0]); return; }
                respond(ctx,request,HttpResponseStatus.NO_CONTENT,"text/plain",new byte[0],cookie);
            } catch (RuntimeException failure) {
                respond(ctx,request,HttpResponseStatus.BAD_REQUEST,"text/plain",new byte[0]);
            }
            return;
        }
        if (path.equals("/ws")) {
            // Allow same-origin proxies and explicitly configured frontend origins.
            // Non-browser wire clients may omit Origin.
            String origin = request.headers().get(HttpHeaderNames.ORIGIN);
            String host = request.headers().get(HttpHeaderNames.HOST);
            if (origin != null && !(origin.equals("http://" + host) || origin.equals("https://" + host)
                    || allowedOrigins.contains(origin))) {
                respond(ctx, request, HttpResponseStatus.FORBIDDEN, "text/plain", new byte[0]);
                return;
            }
            ctx.channel().attr(cn.xjn.xim.attribute.Attributes.WEB).set(true);
            ctx.channel().attr(cn.xjn.xim.attribute.Attributes.COOKIE_TOKEN).set(WebSessions.cookieToken(request.headers().get(HttpHeaderNames.COOKIE)));
            ctx.fireChannelRead(request.retain());
            return;
        }
        if (!request.method().equals(HttpMethod.GET) && !request.method().equals(HttpMethod.HEAD)) {
            respond(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, "text/plain", new byte[0]);
            return;
        }
        if (!path.equals("/api/health")) {
            respond(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(ctx, request, HttpResponseStatus.OK, "application/json; charset=utf-8",
                "{\"status\":\"ok\",\"service\":\"xim-backend\"}".getBytes(StandardCharsets.UTF_8));
    }

    private void respond(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
                         String contentType, byte[] body) {
        respond(ctx, request, status, contentType, body, null);
    }
    private static String cookieHeader(String token, long maxAge) {
        return WebSessions.cookieName()+"="+token+"; Path=/; HttpOnly; SameSite=Strict; Max-Age="+maxAge+(WebSessions.secureCookie()?"; Secure":"");
    }
    private void respond(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
                         String contentType, byte[] body, String cookie) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(request.method().equals(HttpMethod.HEAD) ? new byte[0] : body));
        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (new QueryStringDecoder(request.uri()).path().equals("/api/session") && origin != null
                && (allowedOrigins.contains(origin) || origin.equals("http://"+host) || origin.equals("https://"+host))
                && !"cross-site".equals(request.headers().get("Sec-Fetch-Site"))) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN,origin);
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS,"true");
            response.headers().set(HttpHeaderNames.VARY,"Origin");
            if (request.method().equals(HttpMethod.OPTIONS)) {
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS,"POST, DELETE");
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS,"Content-Type");
            }
        }
        if (cookie != null) response.headers().set(HttpHeaderNames.SET_COOKIE,cookie);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        response.headers().set("X-Content-Type-Options", "nosniff");
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setKeepAlive(response, keepAlive);
        var future = ctx.writeAndFlush(response);
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE);
    }
}
