package com.opsagent.common.mq;

/**
 * 文档异步解析请求载荷，关联文档和可追踪的解析任务。
 *
 * @author heyu
 * @since 2026/8/18
 */
public record DocumentParseRequested(long documentId, long taskId) {}
