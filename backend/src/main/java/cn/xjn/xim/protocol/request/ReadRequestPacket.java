package cn.xjn.xim.protocol.request;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import lombok.Data;
@Data
public class ReadRequestPacket extends Packet {
    private String kind;
    private String id;
    private String sequence;
    @Override public Byte getCommand() { return Command.READ_REQUEST; }
}
