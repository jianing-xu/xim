package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.GroupMessageRequestPacket;
import cn.xjn.xim.protocol.response.GroupMessageResponsePacket;
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
public class GroupMessageRequestHandler extends SimpleChannelInboundHandler<GroupMessageRequestPacket> {

    public static final GroupMessageRequestHandler INSTANCE = new GroupMessageRequestHandler();

    private GroupMessageRequestHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GroupMessageRequestPacket requestPacket) throws Exception {
        Channel channel = ctx.channel();
        String toGroupId = requestPacket.getToGroupId();
        String message = requestPacket.getMessage();
        GroupMessageResponsePacket responsePacket = new GroupMessageResponsePacket();
        responsePacket.setFromGroupId(toGroupId);
        responsePacket.setFromUser(SessionManager.getSession(channel).getUsername());
        responsePacket.setFromUserId(SessionManager.getSession(channel).getUserId());
        responsePacket.setMessage(message);

        ChannelGroup channelGroup = SessionManager.getChannelGroup(toGroupId);
        channelGroup.writeAndFlush(responsePacket);
    }
}
