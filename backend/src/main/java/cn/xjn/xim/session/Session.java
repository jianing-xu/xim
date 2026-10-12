package cn.xjn.xim.session;

import lombok.Data;

/**
 * @author xjn
 * @date 2023-12-27
 */
@Data
public class Session {

    private String userId;

    private String username;

    public Session(String userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public Session() {
    }
}
