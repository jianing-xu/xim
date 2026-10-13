package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.request.MessageRequestPacket;
import cn.xjn.xim.protocol.response.MessageResponsePacket;
import cn.xjn.xim.storage.Storage;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.*;

@ChannelHandler.Sharable
public class MessageRequestHandler extends SimpleChannelInboundHandler<MessageRequestPacket> {
    public static final MessageRequestHandler INSTANCE = new MessageRequestHandler();
    private MessageRequestHandler() {}
    @Override protected void channelRead0(ChannelHandlerContext ctx, MessageRequestPacket request) {
        var sender = SessionManager.getSession(ctx.channel());
        var result = Storage.get().save(sender, request.getToUserId(), null, request.getMessage(), request.getRequestId());
        var saved = result.message();
        var response = new MessageResponsePacket(sender.getUserId(), sender.getUsername(), saved.getMessage());
        response.setRequestId(saved.getClientMessageId());
        response.setSequence(saved.getSequence()); response.setId(saved.getId()); response.setTime(saved.getTime());
        var receiver = SessionManager.getChannel(request.getToUserId());
        if (result.inserted() && receiver != null && receiver.isActive() && SessionManager.hasLogin(receiver)) receiver.writeAndFlush(response);
        HistoryRequestHandler.sent(ctx, request, saved);
    }
}
