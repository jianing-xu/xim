package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * @author xjn
 * @date 2023-12-28
 * compress command handler
 */
@ChannelHandler.Sharable
public class IMServerHandler extends SimpleChannelInboundHandler<Packet> {

    public static final IMServerHandler INSTANCE = new IMServerHandler();

    private final Map<Byte, SimpleChannelInboundHandler<? extends Packet>> HANDLER_MAP = new HashMap<>();

    private IMServerHandler() {
        HANDLER_MAP.put(Command.MESSAGE_REQUEST, MessageRequestHandler.INSTANCE);
        HANDLER_MAP.put(Command.CREATE_GROUP_REQUEST, CreateGroupRequestHandler.INSTANCE);
        HANDLER_MAP.put(Command.JOIN_GROUP_REQUEST, JoinGroupRequestHandler.INSTANCE);
        HANDLER_MAP.put(Command.QUIT_GROUP_REQUEST, QuitGroupRequestHandler.INSTANCE);
        HANDLER_MAP.put(Command.LIST_GROUP_MEMBER_REQUEST, ListGroupMemberRequestHandler.INSTANCE);
        HANDLER_MAP.put(Command.GROUP_MESSAGE_REQUEST, GroupMessageRequestHandler.INSTANCE);
        HANDLER_MAP.put(Command.LOGOUT_REQUEST, LogoutRequestHandler.INSTANCE);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) throws Exception {
        HANDLER_MAP.get(packet.getCommand()).channelRead(ctx, packet);
    }
}
