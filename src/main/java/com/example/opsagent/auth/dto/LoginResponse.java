/*
 * <p>文件名称: LoginResponse.java</p>
 * <p>项目描述: KOCA 金证云原生平台</p>
 * <p>公司名称: 深圳市金证科技股份有限公司</p>
 * <p>版权所有: (C) 2019-2023</p>
 */

package com.example.opsagent.auth.dto;

/**
 * 登录返回token
 *
 * @author heyu 
 * @since 2026-07-23
 */
public record LoginResponse(

    String accessToken,

    String tokenType,

    long expiresIn

) {

}
