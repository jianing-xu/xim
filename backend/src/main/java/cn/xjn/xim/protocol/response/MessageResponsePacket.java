package cn.xjn.xim.protocol.response;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import lombok.Data;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Data
public class MessageResponsePacket extends Packet {

    private String fromUserId;

    private String fromUsername;

    private String message;

    public MessageResponsePacket(String fromUserId, String fromUsername, String message) {
        this.fromUserId = fromUserId;
        this.fromUsername = fromUsername;
        this.message = message;
    }

    public MessageResponsePacket() {
    }

    @Override
    public Byte getCommand() {
        return Command.MESSAGE_RESPONSE;
    }
}
