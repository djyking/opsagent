package com.opsagent.rag;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * RAG 服务访问知识检索接口的声明式客户端。
 *
 * @author heyu
 * @since 2026/8/24
 */
@FeignClient(name = "ops-knowledge-service", url = "${ops.rag.knowledge-url:}")
interface KnowledgeClient {
    @GetMapping("/api/knowledge/internal/search")
    Envelope<List<Map<String, Object>>> search(
            @RequestParam String query,
            @RequestParam int topK,
            @RequestParam(required = false) Long documentId);

    /**
     * 知识服务统一响应包装。
     *
     * @author heyu
     * @since 2026/8/24
     */
    record Envelope<T>(int code, String message, T data, String traceId) {}
}
