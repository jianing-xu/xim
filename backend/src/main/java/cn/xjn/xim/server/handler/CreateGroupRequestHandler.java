package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.CreateGroupRequestPacket;
import cn.xjn.xim.protocol.response.CreateGroupResponsePacket;
import cn.xjn.xim.util.IDUtil;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.DefaultChannelGroup;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
@ChannelHandler.Sharable
public class CreateGroupRequestHandler extends SimpleChannelInboundHandler<CreateGroupRequestPacket> {

    public static final CreateGroupRequestHandler INSTANCE = new CreateGroupRequestHandler();

    private CreateGroupRequestHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, CreateGroupRequestPacket requestPacket) throws Exception {
        List<String> userIds = requestPacket.getUserIds();
        List<String> usernameList = new ArrayList<>();
        DefaultChannelGroup channelGroup = new DefaultChannelGroup(ctx.executor());

        // find user online, add them into the group
        for (String userId : userIds) {
            Channel channel = SessionManager.getChannel(userId);
            if (channel != null) {
                channelGroup.add(channel);
                usernameList.add(SessionManager.getSession(channel).getUsername());
            }
        }

        String groupId = IDUtil.genRandomId();
        CreateGroupResponsePacket createGroupResponsePacket = new CreateGroupResponsePacket();
        createGroupResponsePacket.setSuccess(true);
        createGroupResponsePacket.setGroupId(groupId);
        createGroupResponsePacket.setUsernameList(usernameList);

        SessionManager.bindChannelGroup(groupId, channelGroup);

        channelGroup.writeAndFlush(createGroupResponsePacket);

        log.info("Group created, id is [{}]", createGroupResponsePacket.getGroupId());
        log.info("There are {} in this group", usernameList);
    }
}
