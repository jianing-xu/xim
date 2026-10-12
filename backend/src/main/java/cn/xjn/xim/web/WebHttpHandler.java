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
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(request.method().equals(HttpMethod.HEAD) ? new byte[0] : body));
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
