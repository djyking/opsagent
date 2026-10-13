package com.opsagent.knowledge;

/**
 * 统计用于切片预算的模型近似 Token 数量。
 *
 * @author heyu
 * @since 2026/9/3
 */
public interface TokenCounter {

    int count(String text);
}
