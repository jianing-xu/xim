package cn.xjn.xim.auth;

public final class AccountPolicy {
    private AccountPolicy() {}
    public static final String INVALID_CREDENTIALS = "账号或密码错误，请重试。";
    public static final String LIMITED = "尝试过于频繁，请 5 分钟后重试。";
    public static boolean demoEnabled() {
        return Boolean.parseBoolean(System.getProperty("xim.auth.demo", System.getenv().getOrDefault("XIM_AUTH_DEMO", "false")));
    }
    public static boolean validUsername(String username) {
        return username != null && username.matches("[A-Za-z0-9_]{3,32}");
    }
    public static boolean validLoginPassword(String password) {
        return password != null && !password.isBlank() && password.length() <= 128
                && password.chars().noneMatch(Character::isISOControl);
    }
    public static void validate(String username, String password) {
        if (!validUsername(username)) throw new IllegalArgumentException("账号须为 3–32 位字母、数字或下划线（区分大小写）");
        validatePassword(password);
    }
    public static void validatePassword(String password) {
        if (!validLoginPassword(password) || password.length() < 8)
            throw new IllegalArgumentException("密码须为 8–128 个字符，不能全为空白或包含控制字符");
    }
}
