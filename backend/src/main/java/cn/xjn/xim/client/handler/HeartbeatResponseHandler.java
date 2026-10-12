package cn.xjn.xim.client.handler;

import cn.xjn.xim.protocol.response.HeartbeatResponsePacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * @author xjn
 * @date 2023-12-27
 */
public class HeartbeatResponseHandler extends SimpleChannelInboundHandler<HeartbeatResponsePacket> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HeartbeatResponsePacket responsePacket) throws Exception {

    }
}
