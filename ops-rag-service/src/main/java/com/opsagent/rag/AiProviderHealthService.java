package com.opsagent.rag;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 提供不暴露密钥和原始错误体的模型配置状态与真实连通性诊断。
 *
 * @author heyu
 * @since 2026/9/2
 */
@Service
public class AiProviderHealthService {
    private final LlmClientRouter router;
    private final LlmInvocationService invocationService;

    AiProviderHealthService(
            LlmClientRouter router, LlmInvocationService invocationService) {
        this.router = router;
        this.invocationService = invocationService;
    }

    List<ProviderHealth> configurations() {
        return router.all().stream()
                .map(client -> new ProviderHealth(
                        client.provider(), client.configured(), false, client.model(), null))
                .toList();
    }

    ProviderHealth probe(String provider) {
        LlmClient client = router.byName(provider);
        if (!client.configured()) {
            return new ProviderHealth(client.provider(), false, false, client.model(), "未配置");
        }
        try {
            LlmRequest request = new LlmRequest(
                    "你是连通性检查程序。严格按照用户要求输出。",
                    "你好，请只回答 OK",
                    64);
            LlmResult result = invocationService.invoke(client, "provider-health-probe", request)
                    .result();
            boolean reachable = "OK".equalsIgnoreCase(result.text().trim());
            return new ProviderHealth(
                    client.provider(), true, reachable, result.model(), reachable ? null : "响应不是 OK");
        } catch (AiProviderException exception) {
            return new ProviderHealth(
                    client.provider(), true, false, client.model(), exception.getMessage());
        }
    }

    /**
     * 表示可安全返回给管理员的模型配置与连通状态。
     *
     * @author heyu
     * @since 2026/9/2
     */
    record ProviderHealth(
            String provider, boolean configured, boolean reachable, String model, String lastError) {}
}
