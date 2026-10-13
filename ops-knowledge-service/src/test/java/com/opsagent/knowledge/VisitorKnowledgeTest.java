package com.opsagent.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 验证访客文档和演练复盘的本人隔离、处理额度、幂等保存与体验到期清理。
 *
 * @author heyu
 * @since 2026/9/3
 */
class VisitorKnowledgeTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;
    VisitorKnowledgeService service;
    VisitorKnowledgeLeaseClient leases;
    TicketAccessClient ticketAccess;
    KnowledgeRepository repo;
    EmbeddingClient embedding;
    QdrantVectorStore vectors;
    ElasticsearchVectorStore keywords;
    Instant expiry;
    KnowledgeProperties parseProperties;
    KnowledgeIndexService index;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        String schema = Files.readString(Path.of("../sql/03_ops_knowledge.sql"));
        for (String statement : schema.split(";"))
            if (statement.stripLeading().startsWith("CREATE TABLE")) jdbc.execute(statement);
        String migration =
                Files.readString(Path.of("../sql/upgrade/20260908_visitor_knowledge.sql"));
        for (String statement : migration.split(";"))
            if (!statement.isBlank() && !statement.stripLeading().startsWith("USE "))
                jdbc.execute(statement);
        repo = new KnowledgeRepository(jdbc, new ObjectMapper());
        var props = new KnowledgeProperties();
        props.setStorageRoot(directory.toString());
        parseProperties = props;
        var vectorProps = new VectorProperties();
        vectorProps.setEnabled(true);
        vectorProps.setDimensions(2);
        var counter = new ApproxTokenCounter();
        embedding = mock(EmbeddingClient.class);
        vectors = mock(QdrantVectorStore.class);
        keywords = mock(ElasticsearchVectorStore.class);
        when(embedding.configured()).thenReturn(true);
        when(embedding.model()).thenReturn("test-embedding");
        when(embedding.dimensions()).thenReturn(2);
        when(embedding.embedBatch(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<String> texts = invocation.getArgument(0);
                            return new EmbeddingBatchResult(
                                    texts.stream().map(t -> List.of(0.3, 0.7)).toList(),
                                    "test-embedding",
                                    2,
                                    42);
                        });
        when(keywords.experienceIndex()).thenReturn("keyword_experience");
        when(vectors.experienceCollection()).thenReturn("vector_experience");
        when(keywords.bulkIndex(anyString(), anyList()))
                .thenAnswer(
                        invocation -> {
                            List<ElasticsearchVectorStore.IndexDocument> rows =
                                    invocation.getArgument(1);
                            return new ElasticsearchVectorStore.BulkIndexResult(
                                    rows.stream()
                                            .map(ElasticsearchVectorStore.IndexDocument::chunkId)
                                            .toList(),
                                    Map.of());
                        });
        when(vectors.bulkUpsert(anyString(), anyList()))
                .thenAnswer(
                        invocation -> {
                            List<QdrantVectorStore.IndexPoint> rows = invocation.getArgument(1);
                            return new QdrantVectorStore.BulkUpsertResult(
                                    rows.stream()
                                            .map(QdrantVectorStore.IndexPoint::chunkId)
                                            .toList(),
                                    Map.of());
                        });
        index =
                new KnowledgeIndexService(
                        vectorProps,
                        embedding,
                        keywords,
                        vectors,
                        repo,
                        new ObjectMapper(),
                        counter,
                        new QueryNormalizer(),
                        new SimpleMeterRegistry());
        leases = mock(VisitorKnowledgeLeaseClient.class);
        ticketAccess = mock(TicketAccessClient.class);
        expiry = Instant.now().plusSeconds(86400);
        when(leases.read(anyLong()))
                .thenReturn(new VisitorKnowledgeLeaseClient.Lease(true, expiry));
        service =
                new VisitorKnowledgeService(
                        jdbc,
                        new DataSourceTransactionManager(ds),
                        repo,
                        new LocalFileStorageService(props),
                        new DocumentParserService(props, counter),
                        index,
                        embedding,
                        vectors,
                        keywords,
                        leases,
                        ticketAccess);
        actor(-101);
    }

    @AfterEach
    void after() {
        service.stop();
        index.closeQueryWorkers();
        SecurityContextHolder.clearContext();
    }

    @Test
    void oneGlobalQueryReusesPrivateVectorAndKeepsSeparatePermissionFilteredSearches() {
        long id = upload("本次查询复用的私有验收资料");
        process(id);
        clearInvocations(embedding);
        QueryEmbedding query = index.queryEmbedding(" 私有验收资料 ");
        service.search("私有验收资料", 3, null, query);
        index.search(
                new RetrievalRequest(
                        "私有验收资料", null, null, null, null, Set.of(), false, -101L, false, 3),
                query);
        verify(embedding).embedBatch(List.of("私有验收资料"));
        verify(vectors).experienceSearch(anyList(), eq(-101L), eq(List.of(id)), eq(3), eq(false));
        verify(vectors)
                .vectorSearch(
                        anyList(),
                        argThat(
                                request ->
                                        request.userId() == -101L
                                                && !request.administrator()
                                                && !request.administratorPreview()),
                        anyInt());
        actor(-202);
        assertThat(service.search("私有验收资料", 3, null)).isEmpty();
        verify(embedding).embedBatch(List.of("私有验收资料"));
    }

    static void actor(long id) {
        var roles = List.of("DEMO");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(id, "访客", "test", roles),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_DEMO"))));
    }

    long upload(String body) {
        service.overview();
        return service.upload(
                new MockMultipartFile(
                        "file",
                        "guide.md",
                        "text/markdown",
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    void process(long id) {
        service.requestProcess(id, false);
        service.process(id, -101, false);
    }

    @Test
    void resumesOneLibraryWithoutExtendingTheAbsoluteLease() {
        var first = service.overview();
        when(leases.read(-101))
                .thenReturn(new VisitorKnowledgeLeaseClient.Lease(true, expiry.plusSeconds(3600)));
        var second = service.overview();
        assertThat(second.get("baseId")).isEqualTo(first.get("baseId"));
        assertThat((Instant) second.get("expiresAt"))
                .isCloseTo(expiry, within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(repo.bases()).isEmpty();
    }

    @Test
    void enforcesThreeFilesAndFiveMegabytesOnTheServer() {
        upload("one");
        upload("two");
        upload("three");
        assertThatThrownBy(() -> upload("four")).hasMessageContaining("最多保留 3 份");
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        new MockMultipartFile(
                                                "file",
                                                "big.txt",
                                                "text/plain",
                                                new byte[5 * 1024 * 1024 + 1])))
                .hasMessageContaining("5 MB");
    }

    @Test
    void completedDrillDraftIsLinkedAndReusesTheOwnerExperiencePipeline() {
        long id = retrospective(2053);
        assertThat(repo.document(id))
                .containsEntry("ticket_id", 2053L)
                .containsEntry("create_by", -101L)
                .containsEntry("visibility", "PRIVATE")
                .containsEntry("review_status", "EXPERIENCE");
        assertThat(service.ticketDocuments(2053))
                .extracting(row -> row.get("id"))
                .containsExactly(id);
        assertThat(service.ticketDocuments(2054)).isEmpty();
        assertThat(repo.ticketDocuments(2053, 1, true)).isEmpty();
        verify(ticketAccess).requireCompletedVisitorDrill(2053);
        process(id);
        assertThat(service.ticketDocuments(2053).get(0)).containsEntry("stage", "INDEXED");
        verify(vectors).bulkUpsert(eq("vector_experience"), anyList());
    }

    @Test
    void savingSameDrillReusesItsDocumentEvenAtQuotaAndDeletionAllowsNewDraft() {
        long original = retrospective(2053);
        upload("second");
        upload("third");
        assertThat(retrospective(2053)).isEqualTo(original);
        assertThatThrownBy(() -> retrospective(2054)).hasMessageContaining("最多保留 3 份");
        service.delete(original);
        long replacement = retrospective(2053);
        assertThat(replacement).isNotEqualTo(original);
        assertThat(service.ticketDocuments(2053))
                .extracting(row -> row.get("id"))
                .containsExactly(replacement);
    }

    @Test
    void concurrentDrillSavesCreateExactlyOneLinkedDocument() throws Exception {
        service.overview();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Future<Long>>();
            for (int i = 0; i < 4; i++)
                jobs.add(
                        pool.submit(
                                () -> {
                                    actor(-101);
                                    try {
                                        return retrospective(2053);
                                    } finally {
                                        SecurityContextHolder.clearContext();
                                    }
                                }));
            Set<Long> ids = new HashSet<>();
            for (var job : jobs) ids.add(job.get(10, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(service.ticketDocuments(2053)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deniedTicketCannotBeLinkedAndOtherVisitorsCannotSeeRetrospective() {
        doThrow(
                        new BusinessException(
                                com.opsagent.common.core.ErrorCode.FORBIDDEN, "仅能沉淀本人已完成的隔离演练"))
                .when(ticketAccess)
                .requireCompletedVisitorDrill(2054);
        assertThatThrownBy(() -> retrospective(2054)).hasMessageContaining("本人");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class))
                .isZero();
        long id = retrospective(2053);
        actor(-202);
        assertThat(service.ticketDocuments(2053)).isEmpty();
        assertThatThrownBy(() -> service.requireVisible(id)).hasMessageContaining("无权访问");
        actor(-101);
        when(leases.read(-101)).thenReturn(new VisitorKnowledgeLeaseClient.Lease(false, expiry));
        assertThatThrownBy(() -> service.ticketDocuments(2053)).hasMessageContaining("体验");
        assertThatThrownBy(() -> retrospective(2053)).hasMessageContaining("体验");
    }

    private long retrospective(long ticketId) {
        return service.upload(
                new MockMultipartFile(
                        "file",
                        "event-" + ticketId + "-retrospective.md",
                        "text/markdown",
                        "# 演练复盘\n来自本人已完成演练的可核对草稿"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                ticketId);
    }

    @Test
    void concurrentUploadsCannotBypassQuota() throws Exception {
        service.overview();
        var pool = Executors.newFixedThreadPool(5);
        try {
            var jobs = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 5; i++)
                jobs.add(
                        pool.submit(
                                () -> {
                                    actor(-101);
                                    try {
                                        upload("concurrent");
                                        return true;
                                    } catch (BusinessException expected) {
                                        return false;
                                    } finally {
                                        SecurityContextHolder.clearContext();
                                    }
                                }));
            int accepted = 0;
            for (var job : jobs) if (job.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void otherVisitorCannotReadParseDeleteOrRetrieveOwnersFile() {
        long id = upload("private incident evidence");
        actor(-202);
        service.overview();
        assertThatThrownBy(() -> service.requireVisible(id)).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> service.requestProcess(id, false)).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> service.delete(id)).hasMessageContaining("无权访问");
        assertThatThrownBy(() -> service.search("private", 3, id)).hasMessageContaining("无权访问");
        assertThat(service.search("private", 3, null)).isEmpty();
        verifyNoInteractions(embedding);
    }

    @Test
    void supportsRealIndexPipelineAndOwnerVectorRetrieval() {
        long id = upload("# 私有故障手册\n\n发生紫晶故障时，先检查第七号队列，再联系当班人员。");
        process(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT stage FROM visitor_knowledge_document WHERE document_id=?",
                                String.class,
                                id))
                .isEqualTo("INDEXED");
        verify(embedding).embedBatch(anyList());
        verify(vectors)
                .bulkUpsert(
                        eq("vector_experience"),
                        argThat(
                                points ->
                                        points.stream()
                                                .allMatch(
                                                        p ->
                                                                p.payload()
                                                                                .get("visibility")
                                                                                .equals("PRIVATE")
                                                                        && p.payload()
                                                                                .get("reviewStatus")
                                                                                .equals(
                                                                                        "EXPERIENCE")
                                                                        && p.payload()
                                                                                .get("createBy")
                                                                                .equals(-101L))));
        long chunk = ((Number) repo.chunks(id).get(0).get("id")).longValue();
        when(vectors.experienceSearch(anyList(), eq(-101L), eq(List.of(id)), eq(3), eq(true)))
                .thenReturn(
                        List.of(
                                new RetrievalHit(
                                        "" + chunk,
                                        0.9,
                                        Map.of(
                                                "documentId",
                                                id,
                                                "chunkId",
                                                chunk,
                                                "content",
                                                "stale text"))));
        var result = service.search("紫晶故障", 3, id);
        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .containsEntry("knowledgeScope", "MY_EXPERIENCE")
                .containsEntry("retrievalMode", "VECTOR");
        assertThat(result.get(0).get("content").toString())
                .contains("第七号队列")
                .doesNotContain("stale text");
        assertThat(repo.search("紫晶", 3, -101, false, null)).isEmpty();
    }

    @Test
    void exceedingTextLimitFailsWithoutChunksOrEmbeddings() {
        long id = upload("字".repeat(100_001));
        process(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT error_message FROM visitor_knowledge_document WHERE"
                                        + " document_id=?",
                                String.class,
                                id))
                .contains("10 万字符");
        assertThat(repo.chunks(id)).isEmpty();
        verifyNoInteractions(embedding);
    }

    @Test
    void exceedingChunkLimitFailsWithoutPartialSuccess() {
        parseProperties.getChunk().setTargetTokens(150);
        parseProperties.getChunk().setMaxTokens(200);
        parseProperties.getChunk().setMinTokens(50);
        parseProperties.getChunk().setOverlapTokens(20);
        long id = upload("# 第一章\n\n" + "汉".repeat(90_000));
        process(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT error_message FROM visitor_knowledge_document WHERE"
                                        + " document_id=?",
                                String.class,
                                id))
                .contains("200 个切片");
        assertThat(repo.chunks(id)).isEmpty();
        verifyNoInteractions(embedding);
    }

    @Test
    void ownerMayOnlyQueueOneDocumentAtATime() {
        long one = upload("one");
        long two = upload("two");
        service.requestProcess(one, false);
        assertThatThrownBy(() -> service.requestProcess(two, false))
                .hasMessageContaining("只能处理 1 份");
    }

    @Test
    void revokedExperienceImmediatelyDeniesAccessAndCleanupRemovesFilesChunksAndIndexes()
            throws Exception {
        long id = upload("private cleanup text");
        process(id);
        assertThat(Files.walk(directory).filter(Files::isRegularFile).count()).isEqualTo(1);
        when(leases.read(-101)).thenReturn(new VisitorKnowledgeLeaseClient.Lease(false, expiry));
        assertThatThrownBy(() -> service.requireVisible(id)).hasMessageContaining("体验已结束");
        service.cleanup();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (jdbc.queryForObject(
                                "SELECT cleaned_at FROM visitor_knowledge_document WHERE"
                                        + " document_id=?",
                                Timestamp.class,
                                id)
                        == null
                && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(repo.chunks(id)).isEmpty();
        assertThat(Files.walk(directory).filter(Files::isRegularFile).count()).isZero();
        verify(keywords).deleteExperienceDocument(id);
        verify(vectors).deleteExperienceDocument(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cleaned_at FROM visitor_knowledge_document WHERE"
                                        + " document_id=?",
                                Timestamp.class,
                                id))
                .isNotNull();
    }

    @Test
    void expiryDoesNotDependOnAuthAvailabilityOrCleanup() {
        long id = upload("private");
        jdbc.update(
                "UPDATE visitor_knowledge_space SET expires_at=? WHERE user_id=-101",
                Timestamp.from(Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> service.requireVisible(id)).hasMessageContaining("体验已结束");
        service.cleanup();
        assertThat(repo.document(id)).isNull();
    }

    @Test
    void revocationDuringEmbeddingCannotLeaveRetrievableVectorsOrChunks() {
        long id = upload("some secret content");
        when(embedding.embedBatch(anyList()))
                .thenAnswer(
                        invocation -> {
                            when(leases.read(-101))
                                    .thenReturn(
                                            new VisitorKnowledgeLeaseClient.Lease(false, expiry));
                            return new EmbeddingBatchResult(
                                    List.of(List.of(0.3, 0.7)), "test-embedding", 2, 9);
                        });
        process(id);
        assertThat(repo.document(id)).isNull();
        assertThat(repo.chunks(id)).isEmpty();
        verify(vectors, never()).bulkUpsert(anyString(), anyList());
        verify(vectors).deleteExperienceDocument(id);
    }

    @Test
    void missingEmbeddingUsageRemainsUnknownInsteadOfZero() {
        long id = upload("simple content");
        when(embedding.embedBatch(anyList()))
                .thenReturn(
                        new EmbeddingBatchResult(
                                List.of(List.of(0.3, 0.7)), "test-embedding", 2, -1));
        process(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT embedding_unknown_calls FROM visitor_knowledge_document"
                                        + " WHERE document_id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT embedding_tokens FROM visitor_knowledge_document WHERE"
                                        + " document_id=?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void durableGlobalProcessingSlotsPreventAThirdWorkerAcrossInstances() {
        long first = upload("one");
        actor(-202);
        long second = upload("two");
        actor(-303);
        long third = upload("three");
        service.requestProcess(third, false);
        jdbc.update(
                "UPDATE visitor_knowledge_document SET"
                        + " stage='INDEXING',processing_active=1,started_at=? WHERE document_id IN"
                        + " (?,?)",
                Timestamp.from(Instant.now()),
                first,
                second);
        service.dispatch();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT stage FROM visitor_knowledge_document WHERE document_id=?",
                                String.class,
                                third))
                .isEqualTo("QUEUED");
        verifyNoInteractions(embedding);
    }

    @Test
    void deletionDuringRemoteVectorWriteGetsPurgedAgainByTheWorker() {
        long id = upload("private file");
        when(vectors.bulkUpsert(anyString(), anyList()))
                .thenAnswer(
                        invocation -> {
                            service.delete(id);
                            // The remote response arrives after deletion; the final worker check
                            // must purge it.
                            List<QdrantVectorStore.IndexPoint> points = invocation.getArgument(1);
                            return new QdrantVectorStore.BulkUpsertResult(
                                    points.stream()
                                            .map(QdrantVectorStore.IndexPoint::chunkId)
                                            .toList(),
                                    Map.of());
                        });
        process(id);
        assertThat(repo.document(id)).isNull();
        assertThat(repo.chunks(id)).isEmpty();
        verify(vectors).deleteExperienceDocument(id);
    }

    @Test
    void mysqlZoneLessDatetimeUsesJdbcTimestampConversionInsteadOfAssumingUtc() throws Exception {
        var result = mock(java.sql.ResultSet.class);
        var metadata = mock(java.sql.ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("expires_at");
        when(result.getObject(1)).thenReturn(java.time.LocalDateTime.parse("2026-09-09T15:03:04"));
        Instant actual = Instant.parse("2026-09-09T07:03:04Z");
        when(result.getTimestamp("expires_at")).thenReturn(Timestamp.from(actual));
        assertThat(VisitorKnowledgeService.leaseRow(result, 0).get("expires_at")).isEqualTo(actual);
    }
}
