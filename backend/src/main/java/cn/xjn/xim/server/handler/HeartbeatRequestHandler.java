package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.HeartbeatRequestPacket;
import cn.xjn.xim.protocol.response.HeartbeatResponsePacket;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * @author xjn
 * @date 2023-12-27
 */
@ChannelHandler.Sharable
public class HeartbeatRequestHandler extends SimpleChannelInboundHandler<HeartbeatRequestPacket> {

    public static final HeartbeatRequestHandler INSTANCE = new HeartbeatRequestHandler();

    private HeartbeatRequestHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HeartbeatRequestPacket requestPacket) throws Exception {
        HeartbeatResponsePacket responsePacket = new HeartbeatResponsePacket();
        ctx.writeAndFlush(responsePacket);
    }
}
