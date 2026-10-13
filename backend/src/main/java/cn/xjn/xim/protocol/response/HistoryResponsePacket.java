package cn.xjn.xim.protocol.response;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import lombok.Data;
@Data
public class HistoryResponsePacket extends Packet {
    private String kind; private String id; private java.util.List<cn.xjn.xim.storage.ChatStore.StoredMessage> messages; private String nextBefore; private boolean hasMore; private String nextAfter; private long syncCursor;
    @Override public Byte getCommand() { return Command.HISTORY_RESPONSE; }
}
