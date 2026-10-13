package cn.xjn.xim.protocol.request;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import lombok.Data;
@Data
public class HistoryRequestPacket extends Packet {
    private String kind; private String id; private String before; private String after; private int limit = 50;
    @Override public Byte getCommand() { return Command.HISTORY_REQUEST; }
}
