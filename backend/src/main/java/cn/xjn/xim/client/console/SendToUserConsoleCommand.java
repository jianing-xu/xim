package cn.xjn.xim.client.console;

import cn.xjn.xim.protocol.request.MessageRequestPacket;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
public class SendToUserConsoleCommand implements ConsoleCommand {

    @Override
    public void exec(Scanner sc, Channel channel) {
        log.info("Enter userId and message split by space to send to user:");
        String toUserId = sc.next();
        String msg = sc.next();
        MessageRequestPacket requestPacket = new MessageRequestPacket(toUserId, msg);
        channel.writeAndFlush(requestPacket);
    }
}
