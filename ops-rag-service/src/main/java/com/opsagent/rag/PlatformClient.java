package com.opsagent.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 以请求用户身份读取服务目录，仅接收回答需要的白名单字段。
 *
 * @author heyu
 * @since 2026/9/3
 */
@FeignClient(name = "ops-platform-service", url = "${ops.rag.platform-url:}")
interface PlatformClient {
    @GetMapping("/api/platform/cmdb/cis")
    KnowledgeClient.Envelope<List<Ci>> cis();

    @GetMapping("/api/platform/cmdb/relations")
    KnowledgeClient.Envelope<List<Relation>> relations();

    /**
     * 服务目录安全投影，不接收 endpoint、凭据或自由描述。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ci(String ciCode, String ciName, String ciType, String environment,
              String status, String updateTime) {}

    /**
     * 已登记的有向关系。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Relation(String sourceCiCode, String targetCiCode, String relationType,
                    String createTime) {}
}
