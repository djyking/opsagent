package com.example.opsagent.common.enums;

/**
 * 系统用户启用状态枚举。
 *
 * @author heyu 
 * @since 2026/7/19
 */
public enum AuthRegisterStatusEnum {

    ENABLE("enable"),

    DISABLE("disable");

    private final String code;

    public String getCode() {
        return code;
    }

    public static boolean isEnabled(String status) {
        return ENABLE.code.equalsIgnoreCase(status) || "enabled".equalsIgnoreCase(status);
    }

    AuthRegisterStatusEnum(String code) {
        this.code = code;
    }
}
