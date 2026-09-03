package com.opsagent.rag;

/**
 * 定义可替换的大模型生成客户端契约。
 *
 * @author heyu
 * @since 2026/8/30
 */
public interface LlmClient {
    String provider();

    boolean configured();

    String model();

    LlmResult generate(LlmRequest request);
}
