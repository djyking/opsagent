package com.example.opsagent.ai.retrieval;

import java.util.List;

import com.example.opsagent.ai.config.AiProperties;
import com.example.opsagent.document.dao.DocumentChunkDao;
import com.example.opsagent.document.entity.DocumentChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证关键词检索限定候选数量、相关性排序和 Top K 截断。
 *
 * @author heyu
 * @since 2026/8/16
 */
class ChunkRetrievalServiceTest {

    @Test
    void shouldRankRelevantChunksAndLimitResult() {
        DocumentChunkDao dao = mock(DocumentChunkDao.class);
        AiProperties properties = new AiProperties();
        properties.setTopK(2);
        properties.setCandidateLimit(20);
        DocumentChunk weak = chunk(1L, 10L, 0, "磁盘空间需要检查");
        DocumentChunk strong = chunk(2L, 10L, 1, "磁盘使用率超过90%，先清理磁盘日志和临时文件");
        DocumentChunk unrelated = chunk(3L, 10L, 2, "检查网络连接");
        when(dao.selectCandidates(100L, null, 20)).thenReturn(List.of(weak, unrelated, strong));

        List<ScoredChunk> result = new ChunkRetrievalService(dao, properties)
            .retrieve(100L, null, "磁盘使用率超过90%怎么处理", 2);

        assertThat(result).extracting(candidate -> candidate.chunk().getId()).containsExactly(2L, 1L);
        verify(dao).selectCandidates(100L, null, 20);
    }

    private DocumentChunk chunk(Long id, Long documentId, int index, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        return chunk;
    }
}
