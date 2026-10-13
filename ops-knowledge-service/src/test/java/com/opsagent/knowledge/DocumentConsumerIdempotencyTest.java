package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证历史解析及索引消息不会重复调用外部能力或覆盖现有任务状态。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DocumentConsumerIdempotencyTest {
    private static final long DOCUMENT_ID = 1001L;
    private static final long TASK_ID = 9L;
    private static final int VERSION = 3;
    private static final long DELIVERY_TAG = 71L;
    private static final String EVENT_ID = "document-event-9";
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
    private final KnowledgeService parseService = mock(KnowledgeService.class);
    private final Channel channel = mock(Channel.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldAcknowledgeConsumedIndexEventWithoutReadingOrExecutingTask() throws Exception {
        when(repository.consumed("knowledge-document-indexer", EVENT_ID)).thenReturn(true);

        indexConsumer().consume(message(), channel);

        verify(repository, never()).indexTask(anyLong());
        assertIndexSkipped();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void shouldAcknowledgeTerminalIndexTaskWithoutReopeningIt(String status) throws Exception {
        when(repository.indexTask(TASK_ID)).thenReturn(indexTask(DOCUMENT_ID, VERSION, status, "INDEX"));

        indexConsumer().consume(message(), channel);

        assertIndexSkipped();
    }

    @Test
    void shouldAcknowledgeOldIndexVersionWithoutMutatingReusedTaskId() throws Exception {
        when(repository.indexTask(TASK_ID)).thenReturn(indexTask(DOCUMENT_ID, VERSION + 1, "PENDING", "INDEX"));

        indexConsumer().consume(message(), channel);

        assertIndexSkipped();
    }

    @Test
    void shouldAcknowledgeIndexEventWithDifferentDocumentWithoutMutatingTask() throws Exception {
        when(repository.indexTask(TASK_ID)).thenReturn(indexTask(DOCUMENT_ID + 1, VERSION, "PENDING", "INDEX"));

        indexConsumer().consume(message(), channel);

        assertIndexSkipped();
    }

    @Test
    void shouldAcknowledgeMissingIndexTaskWithoutExecutingDocument() throws Exception {
        indexConsumer().consume(message(), channel);

        assertIndexSkipped();
    }

    @Test
    void shouldAcknowledgeIndexEventPointingToDeleteTask() throws Exception {
        when(repository.indexTask(TASK_ID)).thenReturn(indexTask(DOCUMENT_ID, VERSION, "PENDING", "DELETE"));

        indexConsumer().consume(message(), channel);

        assertIndexSkipped();
    }

    @Test
    void shouldIndexAndCompleteOnlyAfterClaimingExpectedVersion() throws Exception {
        eligibleIndexTask();
        when(repository.validDocumentVersion(DOCUMENT_ID, VERSION)).thenReturn(true);
        when(repository.consumeOnce("knowledge-document-indexer", EVENT_ID)).thenReturn(1);

        indexConsumer().consume(message(), channel);

        InOrder execution = inOrder(repository, indexService, channel);
        execution.verify(repository).claimIndexTask(TASK_ID, VERSION);
        execution.verify(repository).validDocumentVersion(DOCUMENT_ID, VERSION);
        execution.verify(indexService).indexDocument(DOCUMENT_ID);
        execution.verify(repository).consumeOnce("knowledge-document-indexer", EVENT_ID);
        execution.verify(repository).indexTaskSuccess(TASK_ID, VERSION);
        execution.verify(channel).basicAck(DELIVERY_TAG, false);
        assertNoIndexFailure();
    }

    @Test
    void shouldAcknowledgeIndexTaskAlreadyClaimedByCompensationWorker() throws Exception {
        when(repository.indexTask(TASK_ID)).thenReturn(indexTask(DOCUMENT_ID, VERSION, "PROCESSING", "INDEX"));
        when(repository.claimIndexTask(TASK_ID, VERSION)).thenReturn(0);

        indexConsumer().consume(message(), channel);

        verify(repository).claimIndexTask(TASK_ID, VERSION);
        verify(repository, never()).validDocumentVersion(anyLong(), anyInt());
        verifyNoInteractions(indexService);
        assertNoIndexFailure();
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void shouldStopAndAcknowledgeClaimedTaskWhoseDocumentIsObsolete() throws Exception {
        eligibleIndexTask();
        when(repository.validDocumentVersion(DOCUMENT_ID, VERSION)).thenReturn(false);

        indexConsumer().consume(message(), channel);

        verify(repository).indexTaskObsolete(TASK_ID, VERSION);
        verify(repository, never()).indexTaskSuccess(anyLong(), nullable(Integer.class));
        verifyNoInteractions(indexService);
        assertNoIndexFailure();
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void shouldRecordRetryableIndexFailureOnlyForClaimedVersion() throws Exception {
        eligibleIndexTask();
        when(repository.validDocumentVersion(DOCUMENT_ID, VERSION)).thenReturn(true);
        doThrow(new IllegalStateException("temporary failure")).when(indexService).indexDocument(DOCUMENT_ID);
        Message event = message();

        assertThatThrownBy(() -> indexConsumer().consume(event, channel))
                .isInstanceOf(IllegalStateException.class);

        verify(repository).indexTaskFailure(TASK_ID, VERSION, "temporary failure", 10);
        verify(repository, never()).indexTaskSuccess(anyLong(), nullable(Integer.class));
        verify(channel, never()).basicAck(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void shouldNotWriteTaskFailureWhenClaimCouldNotBeCompleted() throws Exception {
        when(repository.indexTask(TASK_ID)).thenReturn(indexTask(DOCUMENT_ID, VERSION, "PENDING", "INDEX"));
        when(repository.claimIndexTask(TASK_ID, VERSION)).thenThrow(new IllegalStateException("database unavailable"));
        Message event = message();

        assertThatThrownBy(() -> indexConsumer().consume(event, channel))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(indexService);
        assertNoIndexFailure();
        verify(channel, never()).basicAck(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void shouldAcknowledgeConsumedParseEventWithoutParsingAgain() throws Exception {
        when(repository.consumed("knowledge-document-parser", EVENT_ID)).thenReturn(true);
        when(repository.parseTask(TASK_ID)).thenReturn(parseTask(DOCUMENT_ID, "QUEUED"));

        parseConsumer().consume(message(), channel);

        assertParseSkipped();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void shouldAcknowledgeTerminalParseTaskWithoutChangingDocument(String status) throws Exception {
        when(repository.parseTask(TASK_ID)).thenReturn(parseTask(DOCUMENT_ID, status));

        parseConsumer().consume(message(), channel);

        assertParseSkipped();
    }

    @Test
    void shouldAcknowledgeParseEventWithDifferentDocumentWithoutParsing() throws Exception {
        when(repository.parseTask(TASK_ID)).thenReturn(parseTask(DOCUMENT_ID + 1, "QUEUED"));

        parseConsumer().consume(message(), channel);

        assertParseSkipped();
    }

    @Test
    void shouldAcknowledgeMissingParseTaskWithoutParsing() throws Exception {
        parseConsumer().consume(message(), channel);

        assertParseSkipped();
    }

    @Test
    void shouldContinueRetryingParseTaskThroughListener() throws Exception {
        when(repository.parseTask(TASK_ID)).thenReturn(parseTask(DOCUMENT_ID, "RETRYING"));
        KnowledgeService.ParsedDocument parsed = new KnowledgeService.ParsedDocument(DOCUMENT_ID, List.of());
        when(parseService.parseFile(DOCUMENT_ID)).thenReturn(parsed);
        when(parseService.completeParse(EVENT_ID, TASK_ID, parsed)).thenReturn(true);

        parseConsumer().consume(message(), channel);

        InOrder execution = inOrder(parseService, channel);
        execution.verify(parseService).parseFile(DOCUMENT_ID);
        execution.verify(parseService).completeParse(EVENT_ID, TASK_ID, parsed);
        execution.verify(channel).basicAck(DELIVERY_TAG, false);
        verify(repository, never()).taskAttemptFailed(anyLong(), anyLong(), nullable(String.class), anyInt());
    }

    @Test
    void shouldRecordParseFailureOnlyAfterMatchingCurrentTask() throws Exception {
        when(repository.parseTask(TASK_ID)).thenReturn(parseTask(DOCUMENT_ID, "QUEUED"));
        when(parseService.parseFile(DOCUMENT_ID)).thenThrow(new IllegalStateException("temporary failure"));
        Message event = message();

        assertThatThrownBy(() -> parseConsumer().consume(event, channel))
                .isInstanceOf(IllegalStateException.class);

        verify(repository).taskAttemptFailed(TASK_ID, DOCUMENT_ID, "temporary failure", 3);
        verify(channel, never()).basicAck(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private void eligibleIndexTask() {
        when(repository.indexTask(TASK_ID)).thenReturn(indexTask(DOCUMENT_ID, VERSION, "PENDING", "INDEX"));
        when(repository.claimIndexTask(TASK_ID, VERSION)).thenReturn(1);
    }

    private void assertIndexSkipped() throws Exception {
        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoInteractions(indexService);
        verify(repository, never()).claimIndexTask(anyLong(), nullable(Integer.class));
        verify(repository, never()).indexTaskSuccess(anyLong(), nullable(Integer.class));
        verify(repository, never()).indexTaskObsolete(anyLong(), nullable(Integer.class));
        assertNoIndexFailure();
    }

    private void assertNoIndexFailure() {
        verify(repository, never()).indexTaskFailure(anyLong(), anyInt(), nullable(String.class), anyInt());
        verify(repository, never()).indexTaskFailure(anyLong(), nullable(String.class), anyInt());
    }

    private void assertParseSkipped() throws Exception {
        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoInteractions(parseService);
        verify(repository, never()).taskAttemptFailed(anyLong(), anyLong(), nullable(String.class), anyInt());
        verify(repository, never()).taskProcessing(anyLong());
        verify(repository, never()).taskSuccess(anyLong());
    }

    private Map<String, Object> indexTask(long documentId, int version, String status, String operation) {
        return Map.of("document_id", documentId, "document_version", version, "status", status, "operation", operation);
    }

    private Map<String, Object> parseTask(long documentId, String status) {
        return Map.of("document_id", documentId, "status", status);
    }

    private Message message() throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(mapper.writeValueAsBytes(Map.of("eventId", EVENT_ID, "payload", Map.of(
                "documentId", DOCUMENT_ID, "taskId", TASK_ID, "documentVersion", VERSION))), properties);
    }

    private DocumentIndexConsumer indexConsumer() {
        return new DocumentIndexConsumer(indexService, repository, mapper, new SimpleMeterRegistry());
    }

    private DocumentParseConsumer parseConsumer() {
        return new DocumentParseConsumer(parseService, repository, mapper, new SimpleMeterRegistry());
    }
}
