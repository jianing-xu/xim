package cn.xjn.xim.protocol.request;
import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import lombok.Data;
@Data
public class ConversationsRequestPacket extends Packet {

    @Override public Byte getCommand() { return Command.CONVERSATIONS_REQUEST; }
}
