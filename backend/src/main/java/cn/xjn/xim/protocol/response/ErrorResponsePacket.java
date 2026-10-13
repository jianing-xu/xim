package cn.xjn.xim.protocol.response;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import lombok.Data;
@Data
public class ErrorResponsePacket extends Packet {
    private String reason;
    @Override public Byte getCommand() { return Command.ERROR_RESPONSE; }
}
