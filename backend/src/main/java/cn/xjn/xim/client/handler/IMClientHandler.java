package cn.xjn.xim.client.handler;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * @author xjn
 * @date 2023-12-28
 */
@ChannelHandler.Sharable
public class IMClientHandler extends SimpleChannelInboundHandler<Packet> {

    public static final IMClientHandler INSTANCE = new IMClientHandler();

    private final Map<Byte, SimpleChannelInboundHandler<? extends Packet>> HANDLER_MAP = new HashMap<>();

    private IMClientHandler() {
        HANDLER_MAP.put(Command.LOGIN_RESPONSE, new LoginResponseHandler());
        HANDLER_MAP.put(Command.MESSAGE_RESPONSE, new MessageResponseHandler());
        HANDLER_MAP.put(Command.CREATE_GROUP_RESPONSE, new CreateGroupResponseHandler());
        HANDLER_MAP.put(Command.JOIN_GROUP_RESPONSE, new JoinGroupResponseHandler());
        HANDLER_MAP.put(Command.QUIT_GROUP_RESPONSE, new QuitGroupResponseHandler());
        HANDLER_MAP.put(Command.LIST_GROUP_MEMBER_RESPONSE, new ListGroupMemberResponseHandler());
        HANDLER_MAP.put(Command.GROUP_MESSAGE_RESPONSE, new GroupMessageResponseHandler());
        HANDLER_MAP.put(Command.LOGOUT_RESPONSE, new LogoutResponseHandler());
        HANDLER_MAP.put(Command.HEARTBEAT_RESPONSE, new HeartbeatResponseHandler());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet packet) throws Exception {
        HANDLER_MAP.get(packet.getCommand()).channelRead(ctx, packet);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        SessionManager.unbindSession(ctx.channel());
        super.channelInactive(ctx);
    }
}
