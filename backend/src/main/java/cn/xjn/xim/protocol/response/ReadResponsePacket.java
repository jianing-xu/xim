package cn.xjn.xim.protocol.response;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import cn.xjn.xim.storage.ChatStore;
import lombok.Data;
@Data
public class ReadResponsePacket extends Packet {
    private ChatStore.Conversation conversation;
    private long syncCursor;
    @Override public Byte getCommand() { return Command.READ_RESPONSE; }
}
