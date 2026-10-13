package cn.xjn.xim.protocol.response;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import lombok.Data;
@Data
public class ConversationsResponsePacket extends Packet {
    private java.util.List<cn.xjn.xim.storage.ChatStore.Conversation> conversations;
    private long syncCursor;
    @Override public Byte getCommand() { return Command.CONVERSATIONS_RESPONSE; }
}
