/*
 * <p>文件名称: AuthRegisterStatusEnum.java</p>
 * <p>项目描述: KOCA 金证云原生平台</p>
 * <p>公司名称: 深圳市金证科技股份有限公司</p>
 * <p>版权所有: (C) 2019-2023</p>
 */

package com.example.opsagent.common.enums;

/**
 * 状态枚举
 *
 * @author heyu 
 * @since 2026/7/19
 */
public enum AuthRegisterStatusEnum {

    ENABLE("enable"),

    DISABLE("disable");

    private String getCode;

    public String getGetCode() {
        return getCode;
    }

    public void setGetCode(String getCode) {
        this.getCode = getCode;
    }

    AuthRegisterStatusEnum(String getCode) {
        this.getCode = getCode;
    }
}
