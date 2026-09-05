package com.opsagent.auth;

import com.baomidou.mybatisplus.annotation.*;

/**
 * 系统用户持久化实体。
 *
 * @author heyu
 * @since 2026/8/5
 */
@TableName("sys_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String password;
    private String displayName;
    private String status;
    @TableLogic private Integer deleted;

    static User registered(String username, String encodedPassword, String displayName) {
        User user = new User();
        user.username = username;
        user.password = encodedPassword;
        user.displayName = displayName;
        user.status = "enable";
        user.deleted = 0;
        return user;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStatus() {
        return status;
    }

    public Integer getDeleted() {
        return deleted;
    }
}
