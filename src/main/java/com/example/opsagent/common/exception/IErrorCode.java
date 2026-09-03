package com.example.opsagent.common.exception;

/**
 * 统一错误码接口。
 *
 * @author heyu
 * @since 2026/7/19
 */
public interface IErrorCode {

    /**
     * 错误码
     *
     * @return
     */
    Integer getCode();

    /**
     * 错误信息
     *
     * @return
     */
    String getMessage();
}
