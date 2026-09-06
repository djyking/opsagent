package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.web.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 审核读取真实解析正文与原文件，验证分页完整性和未发布知识的权限边界。
 *
 * @author heyu
 * @since 2026/9/3
 */
class KnowledgeReviewContentTest {
    @TempDir Path directory;
    private final FileStorageService storage = mock(FileStorageService.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private JdbcTemplate jdbc;
    private KnowledgeRepository repository;
    private KnowledgeReviewContentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:review-"
                + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,original_name VARCHAR(255),"
                + "file_type VARCHAR(16),file_size BIGINT,storage_path VARCHAR(255),version INT,"
                + "status VARCHAR(16),review_status VARCHAR(16),parse_error VARCHAR(500),deleted TINYINT)");
        jdbc.execute("CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,"
                + "chunk_index INT,content CLOB,token_count INT,page_number INT)");
        jdbc.update("INSERT INTO knowledge_document VALUES(1,'审核原文.md','md',36,'review.md',2,"
                + "'PARSED','IN_REVIEW',NULL,0)");
        for (int i = 0; i < 19; i++) jdbc.update("INSERT INTO knowledge_chunk VALUES(?,1,?,?,?,?)",
                i + 1, i, "真实解析内容 " + i + (i == 18 ? " 最后一段不可遗漏" : ""), 20, i / 8 + 1);
        Path original = directory.resolve("review.md");
        Files.writeString(original, "# 原文件标题\n这是未经拼接的原始文档", StandardCharsets.UTF_8);
        when(storage.resolve("review.md")).thenReturn(original);
        repository = spy(new KnowledgeRepository(jdbc, new ObjectMapper()));
        var knowledge = new KnowledgeService(repository, storage, mock(DocumentParserService.class),
                mock(DocumentParsePublisher.class), mock(KnowledgeIndexService.class),
                mock(KnowledgeIndexCompensationService.class), metrics, new KnowledgeProperties(),
                mock(TicketAccessClient.class));
        var properties = new KnowledgeProperties();
        properties.setStorageRoot(directory.toString());
        service = new KnowledgeReviewContentService(knowledge, repository, storage, properties);
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeReviewContentController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        role("OPS");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        metrics.close();
    }

    @Test
    void pagesAllRealChunksAndClampsPastTheLastPage() {
        var first = service.preview(1, 1, 8);
        assertThat(first.total()).isEqualTo(19);
        assertThat(first.version()).isEqualTo(2);
        assertThat(first.reviewStatus()).isEqualTo("IN_REVIEW");
        assertThat(first.sourceAvailable()).isTrue();
        assertThat(first.chunks()).extracting(KnowledgeReviewContentService.Chunk::chunkIndex)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        var last = service.preview(1, 999, 8);
        assertThat(last.pageNum()).isEqualTo(3);
        assertThat(last.chunks()).extracting(KnowledgeReviewContentService.Chunk::chunkIndex)
                .containsExactly(16, 17, 18);
        assertThat(last.chunks().get(2).content()).contains("最后一段不可遗漏");
    }

    @Test
    void fullTextIncludesEveryChunkInOrderForBothExistingReviewerRoles() {
        for (String reviewer : List.of("OPS", "ADMIN")) {
            role(reviewer);
            var text = service.text(1);
            assertThat(text.chunkCount()).isEqualTo(19);
            assertThat(text.text()).startsWith("真实解析内容 0\n\n真实解析内容 1\n")
                    .endsWith("真实解析内容 18 最后一段不可遗漏");
        }
    }

    @Test
    void blocksOrdinaryAndDemoUsersBeforeAnyDocumentOrFileRead() {
        clearInvocations(repository, storage);
        for (String viewer : List.of("USER", "DEMO")) {
            role(viewer);
            assertThatThrownBy(() -> service.preview(1, 1, 8)).isInstanceOfSatisfying(
                    BusinessException.class,
                    failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> service.text(1)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.source(1)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(repository, storage);
    }

    @Test
    void refusesDeletedAndMissingDocumentsIncludingTheirOldChunks() {
        jdbc.update("UPDATE knowledge_document SET deleted=1 WHERE id=1");
        for (long id : List.of(1L, 999L)) {
            assertThatThrownBy(() -> service.preview(id, 1, 8)).hasMessageContaining("文档不存在");
            assertThatThrownBy(() -> service.text(id)).hasMessageContaining("文档不存在");
            assertThatThrownBy(() -> service.source(id)).hasMessageContaining("文档不存在");
        }
        verify(storage, never()).resolve(anyString());
    }

    @Test
    void returnsExplicitEmptyAndOversizeReadingErrorsWithoutDiscardingPagination() {
        jdbc.update("DELETE FROM knowledge_chunk");
        assertThat(service.preview(1, 1, 8).total()).isZero();
        assertThatThrownBy(() -> service.text(1)).hasMessageContaining("尚未生成可读解析正文");
        jdbc.update("INSERT INTO knowledge_chunk VALUES(1,1,0,?,20,NULL)", "文".repeat(2_000_001));
        assertThatThrownBy(() -> service.text(1)).hasMessageContaining("分页切片阅读或下载原文件");
        jdbc.update("UPDATE knowledge_chunk SET content='仍可分页阅读' WHERE id=1");
        doReturn(5001L).when(repository).reviewChunkCount(1);
        assertThatThrownBy(() -> service.text(1)).hasMessageContaining("分页切片");
        assertThat(service.preview(1, 1, 8).chunks()).hasSize(1);
    }

    @Test
    void missingOrInvalidSourceKeepsParsedContentReadable() {
        when(storage.resolve("review.md")).thenReturn(directory.resolve("not-present.pdf"));
        assertThat(service.preview(1, 1, 8).sourceAvailable()).isFalse();
        assertThat(service.text(1).text()).contains("最后一段不可遗漏");
        assertThatThrownBy(() -> service.source(1)).hasMessageContaining("原文件暂不可用");
        when(storage.resolve("review.md")).thenThrow(new IllegalArgumentException("outside storage root"));
        assertThat(service.preview(1, 1, 8).sourceAvailable()).isFalse();
        assertThatThrownBy(() -> service.source(1)).hasMessageNotContaining("outside storage root");
    }

    @Test
    void refusesExistingRealFileOutsideTheConfiguredStorageRoot() throws Exception {
        var properties = new KnowledgeProperties();
        properties.setStorageRoot(Files.createDirectory(directory.resolve("restricted-root")).toString());
        var knowledge = mock(KnowledgeService.class);
        var document = repository.document(1);
        when(knowledge.reviewDocument(1)).thenReturn(document);
        var restricted = new KnowledgeReviewContentService(knowledge, repository, storage, properties);
        // The resolver returns a real, readable file outside the configured root, as an escaped link would.
        assertThat(Files.isReadable(storage.resolve("review.md"))).isTrue();
        assertThat(restricted.preview(1, 1, 8).sourceAvailable()).isFalse();
        assertThatThrownBy(() -> restricted.source(1)).hasMessageContaining("原文件暂不可用");
        assertThat(restricted.text(1).text()).contains("最后一段不可遗漏");
    }

    @Test
    void servesRealSourceBytesAsPrivateAttachmentAndPreviewAsTypedJson() throws Exception {
        mvc.perform(get("/api/knowledge/review/documents/1/source"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(content().bytes(Files.readAllBytes(directory.resolve("review.md"))));
        mvc.perform(get("/api/knowledge/review/documents/1/preview").param("pageNum", "3"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.total").value(19)).andExpect(jsonPath("$.data.chunks.length()").value(3))
                .andExpect(jsonPath("$.data.chunks[2].content").value("真实解析内容 18 最后一段不可遗漏"))
                .andExpect(jsonPath("$.data.storagePath").doesNotExist());
        mvc.perform(get("/api/knowledge/review/documents/1/text"))
                .andExpect(jsonPath("$.data.chunkCount").value(19));
    }

    @Test
    void returnsUnifiedBusinessErrorInsteadOfSourceBytesForUnauthorizedViewer() throws Exception {
        role("DEMO");
        mvc.perform(get("/api/knowledge/review/documents/1/source"))
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private void role(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new OpsPrincipal(20L, "review-reader", "test-id", List.of(role)), null, List.of()));
    }
}
