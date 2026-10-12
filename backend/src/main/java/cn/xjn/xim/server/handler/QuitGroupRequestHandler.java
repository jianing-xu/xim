package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.QuitGroupRequestPacket;
import cn.xjn.xim.protocol.response.QuitGroupResponsePacket;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;

/**
 * @author xjn
 * @date 2023-12-27
 */
@ChannelHandler.Sharable
public class QuitGroupRequestHandler extends SimpleChannelInboundHandler<QuitGroupRequestPacket> {

    public static final QuitGroupRequestHandler INSTANCE = new QuitGroupRequestHandler();

    private QuitGroupRequestHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, QuitGroupRequestPacket requestPacket) throws Exception {
        Channel channel = ctx.channel();
        String groupId = requestPacket.getGroupId();
        ChannelGroup channelGroup = SessionManager.getChannelGroup(groupId);
        if (channelGroup != null) {
            channelGroup.remove(channel);
            QuitGroupResponsePacket responsePacket = new QuitGroupResponsePacket();
            responsePacket.setGroupId(groupId);
            responsePacket.setSuccess(true);
            channel.writeAndFlush(responsePacket);
        }
    }
}
