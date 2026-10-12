package cn.xjn.xim.client.console;

import cn.xjn.xim.protocol.request.GroupMessageRequestPacket;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
public class SendToGroupConsoleCommand implements ConsoleCommand {

    @Override
    public void exec(Scanner sc, Channel channel) {
        log.info("Enter groupId and message split by space to send to group:");
        String groupId = sc.next();
        String msg = sc.next();
        GroupMessageRequestPacket requestPacket = new GroupMessageRequestPacket(groupId, msg);
        channel.writeAndFlush(requestPacket);
    }
}
