package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.JoinGroupRequestPacket;
import cn.xjn.xim.protocol.response.JoinGroupResponsePacket;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import lombok.extern.slf4j.Slf4j;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
@ChannelHandler.Sharable
public class JoinGroupRequestHandler extends SimpleChannelInboundHandler<JoinGroupRequestPacket> {

    public static final JoinGroupRequestHandler INSTANCE = new JoinGroupRequestHandler();

    private JoinGroupRequestHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, JoinGroupRequestPacket requestPacket) throws Exception {
        String groupId = requestPacket.getGroupId();
        ChannelGroup channelGroup = SessionManager.getChannelGroup(groupId);
        Channel channel = ctx.channel();
        JoinGroupResponsePacket responsePacket = new JoinGroupResponsePacket();
        responsePacket.setGroupId(groupId);
        if (channelGroup == null) {
            responsePacket.setSuccess(false);
            responsePacket.setReason("Group does not exist");
            channel.writeAndFlush(responsePacket);
            return;
        }
        channelGroup.add(channel);
        responsePacket.setSuccess(true);
        channel.writeAndFlush(responsePacket);
        log.info("[{}] has joint the group [{}]", SessionManager.getSession(channel).getUsername(), groupId);
    }
}
