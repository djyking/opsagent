package com.opsagent.knowledge;

import java.util.Map;

/**
 * 描述关键词或向量检索通道返回的统一候选结果。
 *
 * @author heyu
 * @since 2026/9/3
 */
record RetrievalHit(String id, double score, Map<String, Object> source) {}
