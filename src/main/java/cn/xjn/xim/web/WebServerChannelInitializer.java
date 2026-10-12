package cn.xjn.xim.web;

import cn.xjn.xim.server.handler.*;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/** HTTP assets and JSON WebSocket transport share the existing IM business handlers. */
public class WebServerChannelInitializer extends ChannelInitializer<NioSocketChannel> {
    public static final int MAX_FRAME_BYTES = 64 * 1024;

    @Override
    protected void initChannel(NioSocketChannel channel) {
        channel.pipeline().addLast(new HttpServerCodec());
        channel.pipeline().addLast(new HttpObjectAggregator(MAX_FRAME_BYTES));
        channel.pipeline().addLast(new WebHttpHandler());
        channel.pipeline().addLast(new WebSocketServerProtocolHandler("/ws", null, false, MAX_FRAME_BYTES));
        channel.pipeline().addLast(new WebSocketFrameAggregator(MAX_FRAME_BYTES));
        channel.pipeline().addLast(new WebPacketEncoder());
        channel.pipeline().addLast(new WebRequestHandler());
        channel.pipeline().addLast(LoginRequestHandler.INSTANCE);
        channel.pipeline().addLast(HeartbeatRequestHandler.INSTANCE);
        channel.pipeline().addLast(AuthHandler.INSTANCE);
        channel.pipeline().addLast(IMServerHandler.INSTANCE);
        channel.pipeline().addLast(new WebExceptionHandler());
    }
}
