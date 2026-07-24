/*
 * <p>文件名称: IErrorCode.java</p>
 * <p>项目描述: KOCA 金证云原生平台</p>
 * <p>公司名称: 深圳市金证科技股份有限公司</p>
 * <p>版权所有: (C) 2019-2023</p>
 */

package com.example.opsagent.common.exception;

/**
 * 枚举定义接口
 *
 * @author heyu 
 * @since 2026/7/19
 */
public interface IErrorCode {

    /**
     * 错误码
     * @return
     */
    Integer getCode();

    /**
     * 错误信息
     * @return
     */
    String getMessage();

}

