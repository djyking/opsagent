package com.opsagent.rag;

import com.opsagent.common.core.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 登录用户可读取的模型选择目录，不返回地址、密钥或未经探测的连通性结论。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
public class AiProviderCatalogController {
    private final AiProperties properties;

    AiProviderCatalogController(AiProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/rag/providers")
    ApiResponse<Catalog> providers() {
        List<Provider> rows =
                AiProperties.SUPPORTED.stream()
                        .map(
                                name -> {
                                    var settings = properties.settings(name);
                                    boolean available =
                                            properties.isEnabled() && settings.selectable();
                                    return new Provider(
                                            name,
                                            settings.getModel(),
                                            available,
                                            available
                                                    ? "已配置"
                                                    : properties.isEnabled()
                                                            ? "尚未配置完成"
                                                            : "AI 生成未启用");
                                })
                        .toList();
        String configuredDefault = properties.resolveProvider(null);
        String defaultProvider =
                rows.stream()
                        .filter(row -> row.available() && row.provider().equals(configuredDefault))
                        .map(Provider::provider)
                        .findFirst()
                        .orElse(null);
        return ApiResponse.success(new Catalog(defaultProvider, rows));
    }

    /**
     * 安全模型摘要。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Provider(String provider, String model, boolean available, String status) {}

    /**
     * 模型可选项；配置有效不代表已执行付费连通性探测。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Catalog(String defaultProvider, List<Provider> providers) {}
}
