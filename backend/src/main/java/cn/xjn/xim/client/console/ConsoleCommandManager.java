package cn.xjn.xim.client.console;

import cn.xjn.xim.util.SessionManager;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Slf4j
public class ConsoleCommandManager implements ConsoleCommand {

    private static final Map<String, ConsoleCommand> consoleCommandMap = new HashMap<>();

    public ConsoleCommandManager() {
        consoleCommandMap.put("sendToUser", new SendToUserConsoleCommand());
        consoleCommandMap.put("logout", new LogoutConsoleCommand());
        consoleCommandMap.put("createGroup", new CreateGroupConsoleCommand());
        consoleCommandMap.put("joinGroup", new JoinGroupConsoleCommand());
        consoleCommandMap.put("listGroupMember", new ListGroupMemberConsoleCommand());
        consoleCommandMap.put("quitGroup", new QuitGroupConsoleCommand());
        consoleCommandMap.put("sendToGroup", new SendToGroupConsoleCommand());
    }

    @Override
    public void exec(Scanner sc, Channel channel) {
        if (!SessionManager.hasLogin(channel)) {
            log.warn("Not logged in!");
            return;
        }
        String command = sc.next();
        if (command.equalsIgnoreCase("exit")) {
            channel.close();
            System.exit(1);
        }
        ConsoleCommand consoleCommand = consoleCommandMap.get(command);
        if (consoleCommand != null) {
            consoleCommand.exec(sc, channel);
        } else {
            log.warn("Command [{}] don't exist!", command);
        }
    }
}
