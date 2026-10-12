package cn.xjn.xim.client.console;

import cn.xjn.xim.protocol.request.LogoutRequestPacket;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
public class LogoutConsoleCommand implements ConsoleCommand {

    @Override
    public void exec(Scanner sc, Channel channel) {
        LogoutRequestPacket requestPacket = new LogoutRequestPacket();
        channel.writeAndFlush(requestPacket);
        waitForLoginResponse();
    }

    private void waitForLoginResponse() {
        try {
            // TODO doSomething
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException ignored) {
        }
    }
}
