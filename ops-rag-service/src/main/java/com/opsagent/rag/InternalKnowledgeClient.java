package com.opsagent.rag;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

/**
 * 只向知识服务根内部路径发送为该服务签发的短期 Actor Token。
 *
 * @author heyu
 * @since 2026/9/3
 */
@FeignClient(
        name = "ops-knowledge-service",
        contextId = "agentKnowledge",
        url = "${ops.rag.knowledge-url:}")
interface InternalKnowledgeClient {
    @PostMapping("/internal/agent/search")
    KnowledgeClient.Envelope<List<Map<String, Object>>> search(
            @RequestHeader("Authorization") String authorization,
            @RequestBody InternalAgentDtos.SearchRequest request);
}
