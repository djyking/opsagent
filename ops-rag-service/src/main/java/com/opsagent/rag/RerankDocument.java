package com.opsagent.rag;

/**
 * 使用稳定候选序号关联远程 Rerank 请求与响应。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record RerankDocument(
        int candidateIndex,
        long chunkId,
        String title,
        String headingPath,
        String content,
        String serviceName) {

    String passage() {
        return "标题：" + title + "\n章节：" + headingPath + "\n正文：" + content;
    }
}
