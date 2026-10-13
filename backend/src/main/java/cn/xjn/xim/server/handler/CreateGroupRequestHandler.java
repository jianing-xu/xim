package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.CreateGroupRequestPacket;
import cn.xjn.xim.protocol.response.CreateGroupResponsePacket;
import cn.xjn.xim.storage.Storage;
import cn.xjn.xim.util.*;
import io.netty.channel.*;
import java.util.LinkedHashSet;

@ChannelHandler.Sharable
public class CreateGroupRequestHandler extends SimpleChannelInboundHandler<CreateGroupRequestPacket> {
    public static final CreateGroupRequestHandler INSTANCE = new CreateGroupRequestHandler();
    private CreateGroupRequestHandler() {}
    @Override protected void channelRead0(ChannelHandlerContext ctx, CreateGroupRequestPacket request) {
        var ids = new LinkedHashSet<String>();
        ids.add(SessionManager.getSession(ctx.channel()).getUserId());
        if (request.getUserIds() == null || request.getUserIds().size() > 50) throw new IllegalArgumentException("成员列表无效");
        for (String id : request.getUserIds()) {
            if (!Storage.get().hasUser(id)) throw new IllegalArgumentException("成员不存在");
            ids.add(id);
        }
        String groupId = IDUtil.genRandomId();
        Storage.get().createGroup(groupId, ids);
        var response = new CreateGroupResponsePacket();
        response.setSuccess(true); response.setGroupId(groupId);
        var members = Storage.get().members(groupId);
        response.setUsernameList(ids.stream().map(id -> members.stream()
                .filter(member -> member.getUserId().equals(id)).findFirst().orElseThrow().getUsername()).toList());
        SessionManager.getChannelGroup(groupId).writeAndFlush(response);
    }
}
