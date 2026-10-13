package cn.xjn.xim.protocol.response;

import cn.xjn.xim.protocol.Packet;
import cn.xjn.xim.protocol.command.Command;
import lombok.Data;

/**
 * @author xjn
 * @date 2023-12-26
 */
@Data
public class LoginResponsePacket extends Packet {

    private String userId;

    private String username;

    private boolean success;

    private String reason;
    private String sessionTicket;
    private String connectionId;
    private long expiresAt;

    @Override
    public Byte getCommand() {
        return Command.LOGIN_RESPONSE;
    }
}
