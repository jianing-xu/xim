package cn.xjn.xim.web;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Serves only explicitly listed classpath resources; no filesystem path resolution. */
public class WebHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Map<String, String> ASSETS = Map.of(
            "/", "index.html", "/index.html", "index.html", "/app.js", "app.js", "/styles.css", "styles.css");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws IOException {
        String path = new QueryStringDecoder(request.uri()).path();
        if (!request.decoderResult().isSuccess()) {
            respond(ctx, request, HttpResponseStatus.BAD_REQUEST, "text/plain; charset=utf-8", "Bad request".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.equals("/ws")) {
            // Browser connections are same-origin. Non-browser wire clients may omit Origin.
            String origin = request.headers().get(HttpHeaderNames.ORIGIN);
            String host = request.headers().get(HttpHeaderNames.HOST);
            if (origin != null && !(origin.equals("http://" + host) || origin.equals("https://" + host))) {
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
        String asset = ASSETS.get(path);
        if (asset == null) {
            respond(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream input = getClass().getResourceAsStream("/web/" + asset)) {
            if (input == null) {
                respond(ctx, request, HttpResponseStatus.NOT_FOUND, "text/plain", new byte[0]);
                return;
            }
            String contentType = asset.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : asset.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
            respond(ctx, request, HttpResponseStatus.OK, contentType, input.readAllBytes());
        }
    }

    private void respond(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
                         String contentType, byte[] body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(request.method().equals(HttpMethod.HEAD) ? new byte[0] : body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        response.headers().set("X-Content-Type-Options", "nosniff");
        response.headers().set("Content-Security-Policy", "default-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setKeepAlive(response, keepAlive);
        var future = ctx.writeAndFlush(response);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
