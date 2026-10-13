package cn.xjn.xim.protocol.response;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import lombok.Data;
@Data
public class SentResponsePacket extends Packet {
    private String id; private long time;
    private long sequence;
    @Override public Byte getCommand() { return Command.SENT_RESPONSE; }
}
