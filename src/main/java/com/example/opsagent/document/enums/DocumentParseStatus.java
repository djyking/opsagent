package com.example.opsagent.document.enums;

/**
 * 定义文档从待解析到成功或失败的处理状态。
 *
 * @author heyu
 * @since 2026/8/16
 */
public enum DocumentParseStatus {
    PENDING,
    PARSING,
    SUCCESS,
    FAILED
}
