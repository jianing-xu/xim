package cn.xjn.xim.auth;

import cn.xjn.xim.storage.ChatStore;
import cn.xjn.xim.storage.DatabaseConfig;
import java.util.Arrays;

/** Offline operator tool. Credentials are read from a terminal, never arguments or logs. */
public final class AccountAdmin {
    public static void main(String[] args) {
        if (args.length != 1 || !AccountPolicy.validUsername(args[0]) || System.console() == null) {
            System.err.println("在交互终端运行 account-admin.sh <账号>，先停止服务");
            System.exit(1);
        }
        char[] password = System.console().readPassword("新密码（8–128 个字符）: ");
        char[] confirmation = System.console().readPassword("确认新密码: ");
        try {
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                System.err.println("两次密码不一致"); System.exit(1);
            }
            AccountPolicy.validate(args[0], new String(password));
            try (var store = new ChatStore(DatabaseConfig.fromEnvironment(), false)) {
                store.provisionAccount(args[0], new String(password));
            }
            System.out.println("账号已创建或密码已更新，原有身份及历史已保留");
        } catch (RuntimeException failure) {
            System.err.println(failure instanceof IllegalArgumentException ? failure.getMessage() : "账号操作失败，请检查数据库配置与权限");
            System.exit(1);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
