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
        fitHistory(ctx, request, response);
        ctx.writeAndFlush(response);
    }
    private static void fitHistory(ChannelHandlerContext ctx, HistoryRequestPacket request, HistoryResponsePacket response) {
        int budget = Boolean.TRUE.equals(ctx.channel().attr(cn.xjn.xim.attribute.Attributes.WEB).get())
                ? 64 * 1024 : cn.xjn.xim.codec.Spliter.MAX_FRAME_BYTES - cn.xjn.xim.codec.PacketCodec.HEADER_LENGTH;
        if (cn.xjn.xim.protobuf.ProtocolWire.encodePacket(response).length <= budget) return;
        var all = response.getMessages(); boolean forward = request.getAfter() != null;
        int low = 1, high = all.size(), best = 0;
        while (low <= high) {
            int size = (low + high) / 2;
            response.setMessages(new java.util.ArrayList<>(forward ? all.subList(0,size) : all.subList(all.size()-size,all.size())));
            response.setHasMore(true);
            response.setNextBefore(Long.toString(response.getMessages().getFirst().getSequence()));
            response.setNextAfter(Long.toString(response.getMessages().getLast().getSequence()));
            if (cn.xjn.xim.protobuf.ProtocolWire.encodePacket(response).length <= budget) { best = size; low = size+1; } else high = size-1;
        }
        if (best == 0) throw new IllegalArgumentException("单条历史消息超过传输上限，请联系管理员");
        response.setMessages(new java.util.ArrayList<>(forward ? all.subList(0,best) : all.subList(all.size()-best,all.size())));
        response.setNextBefore(Long.toString(response.getMessages().getFirst().getSequence()));
        response.setNextAfter(Long.toString(response.getMessages().getLast().getSequence()));
    }
    public static void conversations(ChannelHandlerContext ctx, Packet request) {
        var source=Storage.get().conversations(SessionManager.getSession(ctx.channel()).getUserId());
        long cursor=Storage.get().syncCursor();String snapshot=java.util.UUID.randomUUID().toString();
        int budget=Boolean.TRUE.equals(ctx.channel().attr(cn.xjn.xim.attribute.Attributes.WEB).get())
                ? 64*1024 : cn.xjn.xim.codec.Spliter.MAX_FRAME_BYTES-cn.xjn.xim.codec.PacketCodec.HEADER_LENGTH;
        var chunks=new java.util.ArrayList<java.util.List<ChatStore.Conversation>>();
        var chunk=new java.util.ArrayList<ChatStore.Conversation>();int size=512;
        for(var chat:source) {
            var last=chat.getLastMessage();
            if(last!=null) {
                String text=last.getMessage();int points=text.codePointCount(0,text.length());
                String preview=points>120?text.substring(0,text.offsetByCodePoints(0,120))+"…":text;
                chat.setLastMessage(new ChatStore.StoredMessage(last.getId(),last.getSequence(),last.getFromUserId(),last.getFromUsername(),preview,last.getTime(),last.getClientMessageId()));
            }
            int bytes=cn.xjn.xim.protobuf.ProtocolWire.conversationSize(chat)+5;
            if(size+bytes>budget&&!chunk.isEmpty()){chunks.add(chunk);chunk=new java.util.ArrayList<>();size=512;}
            if(bytes+512>budget) throw new IllegalArgumentException("会话摘要超过传输上限");
            chunk.add(chat);size+=bytes;
        }
        if(!chunk.isEmpty()||chunks.isEmpty()) chunks.add(chunk);
        for(int i=0;i<chunks.size();i++) {
            var response=new ConversationsResponsePacket();response.setRequestId(request.getRequestId());response.setSyncCursor(cursor);
            response.setSnapshotId(snapshot);response.setPart(i);response.setHasMore(i<chunks.size()-1);response.setConversations(chunks.get(i));
            ctx.writeAndFlush(response);
        }
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
