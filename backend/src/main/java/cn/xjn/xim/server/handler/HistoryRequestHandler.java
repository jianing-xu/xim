package cn.xjn.xim.server.handler;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.storage.*;
import cn.xjn.xim.util.SessionManager;
import io.netty.channel.*;

@ChannelHandler.Sharable
public final class HistoryRequestHandler extends SimpleChannelInboundHandler<HistoryRequestPacket> {
    public static final HistoryRequestHandler INSTANCE = new HistoryRequestHandler();
    @Override protected void channelRead0(ChannelHandlerContext ctx, HistoryRequestPacket request) {
        var history = Storage.get().history(SessionManager.getSession(ctx.channel()).getUserId(),
                request.getKind(), request.getId(), request.getBefore(), request.getAfter(), request.getLimit());
        var response = new HistoryResponsePacket();
        response.setRequestId(request.getRequestId()); response.setKind(request.getKind()); response.setId(request.getId());
        response.setMessages(history.messages()); response.setNextBefore(history.nextBefore()); response.setHasMore(history.hasMore());
        response.setNextAfter(history.nextAfter()); response.setSyncCursor(history.syncCursor());
        ctx.writeAndFlush(response);
    }
    public static void conversations(ChannelHandlerContext ctx, Packet request) {
        var response = new ConversationsResponsePacket(); response.setRequestId(request.getRequestId());
        response.setConversations(Storage.get().conversations(SessionManager.getSession(ctx.channel()).getUserId()));
        response.setSyncCursor(Storage.get().syncCursor());
        ctx.writeAndFlush(response);
    }
    public static void read(ChannelHandlerContext ctx, ReadRequestPacket request) {
        var response = new ReadResponsePacket(); response.setRequestId(request.getRequestId());
        response.setConversation(Storage.get().markRead(SessionManager.getSession(ctx.channel()).getUserId(),
                request.getKind(),request.getId(),request.getSequence()));
        response.setSyncCursor(Storage.get().syncCursor()); ctx.writeAndFlush(response);
    }
    public static void sent(ChannelHandlerContext ctx, Packet request, ChatStore.StoredMessage saved) {
        if (request.getRequestId() == null) return; // Existing TCP clients have no unsolicited acknowledgement.
        var response = new SentResponsePacket(); response.setRequestId(request.getRequestId());
        response.setSequence(saved.getSequence()); response.setId(saved.getId()); response.setTime(saved.getTime()); ctx.writeAndFlush(response);
    }
}
