package com.opsagent.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 透传当前用户身份，只读取实际工作流目录的公开名称和已发布版本。
 *
 * @author heyu
 * @since 2026/9/3
 */
@FeignClient(name = "ops-agent-service", url = "${ops.rag.automation-url:}")
interface AutomationCatalogClient {
    @GetMapping("/api/automation/definitions")
    KnowledgeClient.Envelope<List<Definition>> definitions();

    /**
     * @author heyu
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Definition(String id, String name, int published_version) {}
}
