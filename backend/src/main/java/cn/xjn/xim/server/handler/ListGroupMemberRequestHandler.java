package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.ListGroupMemberRequestPacket;
import cn.xjn.xim.protocol.response.ListGroupMemberResponsePacket;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
@ChannelHandler.Sharable
public class ListGroupMemberRequestHandler extends SimpleChannelInboundHandler<ListGroupMemberRequestPacket> {

    public static final ListGroupMemberRequestHandler INSTANCE = new ListGroupMemberRequestHandler();

    private ListGroupMemberRequestHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ListGroupMemberRequestPacket requestPacket) throws Exception {
        String groupId = requestPacket.getGroupId();
        ChannelGroup channelGroup = SessionManager.getChannelGroup(groupId);
        List<Session> sessions = new ArrayList<>();
        for (Channel memberChannel : channelGroup) {
            Session session = SessionManager.getSession(memberChannel);
            sessions.add(session);
        }

        ListGroupMemberResponsePacket responsePacket = new ListGroupMemberResponsePacket();
        responsePacket.setGroupId(groupId);
        responsePacket.setSessions(sessions);
        ctx.channel().writeAndFlush(responsePacket);
    }
}
