package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.MessageRequestPacket;
import cn.xjn.xim.protocol.response.MessageResponsePacket;
import cn.xjn.xim.session.Session;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
@ChannelHandler.Sharable
public class MessageRequestHandler extends SimpleChannelInboundHandler<MessageRequestPacket> {

    public static final MessageRequestHandler INSTANCE = new MessageRequestHandler();

    private MessageRequestHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessageRequestPacket messageRequestPacket) throws Exception {
        // find message sender's info
        Session session = SessionManager.getSession(ctx.channel());

        // assemble message response packet
        MessageResponsePacket messageResponsePacket = new MessageResponsePacket();
        messageResponsePacket.setFromUserId(session.getUserId());
        messageResponsePacket.setFromUsername(session.getUsername());
        messageResponsePacket.setMessage(messageRequestPacket.getMessage());

        // find message receiver's channel
        Channel receiverChannel = SessionManager.getChannel(messageRequestPacket.getToUserId());
        if (receiverChannel != null && SessionManager.hasLogin(receiverChannel)) {
            receiverChannel.writeAndFlush(messageResponsePacket);
        } else {
            log.info("Send message failed, [{}] not online", messageRequestPacket.getToUserId());
        }
    }
}
