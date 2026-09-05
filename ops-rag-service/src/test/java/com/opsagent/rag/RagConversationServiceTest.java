package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 通过真实 SQL 与事务代理验证账号隔离、连续问答和异步回调的持久化约束。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RagConversationServiceTest {
    private JdbcTemplate jdbc;
    private RagConversationService service;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource datasource = new DriverManagerDataSource(
                "jdbc:h2:mem:conversation-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(datasource);
        String schema = Files.readString(Path.of("..", "sql", "13_rag_conversations.sql"))
                .replace("USE ops_rag;", "")
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        for (String statement : schema.split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
        RagConversationService target = new RagConversationService(jdbc, new ObjectMapper().findAndRegisterModules());
        TransactionInterceptor transaction = new TransactionInterceptor();
        transaction.setTransactionManager(new DataSourceTransactionManager(datasource));
        transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(transaction);
        service = (RagConversationService) proxy.getProxy();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameRoleNeverGrantsReadOrWriteAccessToAnotherAccountsConversation() {
        RagService rag = mock(RagService.class);
        RagStreamingService streaming = mock(RagStreamingService.class);
        RagRateLimiter limiter = mock(RagRateLimiter.class);
        RagConversationController controller = new RagConversationController(service, rag, streaming, limiter);
        login(1);
        String id = controller.create(new RagConversationController.CreateRequest("账号一私有内容")).data().id();
        long turnId = service.begin(id, 1, "私有问题");
        login(2);
        assertThat(controller.list(1, 20).data().records()).isEmpty();
        assertNotFound(() -> controller.messages(id, null));
        assertNotFound(() -> controller.rename(id, new RagConversationController.RenameRequest("篡改")));
        assertNotFound(() -> controller.delete(id));
        assertNotFound(() -> controller.ask(id, new RagConversationController.QuestionRequest("读取", 5, null)));
        assertNotFound(() -> service.context(id, 2));
        assertNotFound(() -> service.begin(id, 2, "越权提问"));
        assertNotFound(() -> service.complete(id, 2, turnId, answer("越权答案", true)));
        service.fail(id, 2, turnId, "越权失败回调");
        assertThat(service.turns(id, 1, null).records().get(0).status()).isEqualTo("PROCESSING");
        assertThat(service.owned(id, 1).title()).isEqualTo("账号一私有内容");
        verifyNoInteractions(rag, streaming, limiter);
        String second = controller.create(new RagConversationController.CreateRequest("账号二")).data().id();
        assertThat(jdbc.queryForObject("SELECT user_id FROM rag_conversation WHERE id=?", Long.class, second))
                .isEqualTo(2);
        assertThat(controller.list(1, 20).data().records()).extracting(RagConversationService.Conversation::id)
                .containsExactly(second);
    }

    @Test
    void persistsConsecutiveAnswersAndBuildsContextOnlyFromCompletedTurns() {
        String id = service.create(1, null).id();
        long first = service.begin(id, 1, "第一问");
        service.complete(id, 1, first, answer("第一答", true));
        long second = service.begin(id, 1, "第二问");
        assertThat(service.context(id, 1)).contains("第一问", "第一答").doesNotContain("第二问");
        service.complete(id, 1, second, answer("第二答", true));
        assertThat(service.owned(id, 1).title()).isEqualTo("第一问");
        assertThat(service.turns(id, 1, null).records()).extracting(RagConversationService.Turn::question)
                .containsExactly("第一问", "第二问");
        assertThat(service.turns(id, 1, null).records().get(1).result().answer()).isEqualTo("第二答");
        assertThat(service.context(id, 1)).contains("用户：第一问\n助手：第一答", "用户：第二问\n助手：第二答");
    }

    @Test
    void paginatesEveryHistoricalTurnWithoutOverlapAndListsOnlyOwnedConversations() {
        String id = service.create(1, "历史").id();
        for (int index = 1; index <= 45; index++) {
            long turn = service.begin(id, 1, "问题 " + index);
            service.complete(id, 1, turn, answer("回答 " + index, true));
        }
        var newest = service.turns(id, 1, null);
        var older = service.turns(id, 1, newest.records().get(0).id());
        var oldest = service.turns(id, 1, older.records().get(0).id());
        assertThat(newest.records()).hasSize(20);
        assertThat(older.records()).hasSize(20);
        assertThat(oldest.records()).hasSize(5);
        assertThat(newest.hasMore()).isTrue();
        assertThat(older.hasMore()).isTrue();
        assertThat(oldest.hasMore()).isFalse();
        List<Long> ids = new ArrayList<>();
        for (var page : List.of(oldest, older, newest)) {
            ids.addAll(page.records().stream().map(RagConversationService.Turn::id).toList());
        }
        assertThat(ids).isSorted();
        assertThat(new HashSet<>(ids)).hasSize(45);
        assertThat(service.context(id, 1)).contains("问题 40", "问题 45").doesNotContain("问题 39");
        for (int index = 0; index < 22; index++) service.create(1, "会话 " + index);
        service.create(2, "其他账号");
        assertThat(service.list(1, 1, 20).total()).isEqualTo(23);
        assertThat(service.list(1, 2, 20).records()).hasSize(3);
    }

    @Test
    void logicalDeletionHidesReadsAndPreventsLateCallbacksFromRestoringTheConversation() {
        String id = service.create(1, "删除测试").id();
        long turn = service.begin(id, 1, "还在生成的问题");
        service.delete(id, 1);
        assertThat(service.list(1, 1, 20).total()).isZero();
        assertNotFound(() -> service.owned(id, 1));
        assertNotFound(() -> service.turns(id, 1, null));
        assertNotFound(() -> service.context(id, 1));
        assertNotFound(() -> service.rename(id, 1, "恢复"));
        assertNotFound(() -> service.begin(id, 1, "继续"));
        assertNotFound(() -> service.complete(id, 1, turn, answer("迟到回答", true)));
        service.fail(id, 1, turn, "迟到失败");
        assertThat(jdbc.queryForObject("SELECT deleted FROM rag_conversation WHERE id=?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT answer FROM rag_conversation_turn WHERE id=?", String.class, turn))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_conversation_turn WHERE id=?", Long.class, turn))
                .isOne();
    }

    @Test
    void concurrentBeginsAllowExactlyOneProcessingTurnAndBusyConflictsLeaveNoExtraRows() throws Exception {
        String id = service.create(1, "并发").id();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("启动屏障超时");
                    try {
                        service.begin(id, 1, "同一会话并发提问");
                        return true;
                    } catch (BusinessException exception) {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get(15, TimeUnit.SECONDS)) succeeded++;
            }
            assertThat(succeeded).isOne();
            assertThat(service.turns(id, 1, null).records()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void incompleteFailuresAndStaleCallbacksNeverOverwriteCompletedOrOtherTurns() {
        String id = service.create(1, "状态测试").id();
        String other = service.create(1, "其他会话").id();
        long first = service.begin(id, 1, "完整问题");
        service.complete(other, 1, first, answer("错误会话回调", true));
        assertThat(service.turns(id, 1, null).records().get(0).status()).isEqualTo("PROCESSING");
        service.complete(id, 1, first, answer("完整答案", true));
        service.complete(id, 1, first, answer("重复答案", true));
        service.fail(id, 1, first, "迟到的错误");
        long second = service.begin(id, 1, "未完整问题");
        service.complete(id, 1, second, answer("不完整答案", false));
        long third = service.begin(id, 1, "失败问题");
        service.fail(id, 1, third, "x".repeat(600));
        var rows = service.turns(id, 1, null).records();
        assertThat(rows).extracting(RagConversationService.Turn::status)
                .containsExactly("COMPLETE", "INCOMPLETE", "INTERRUPTED");
        assertThat(rows.get(0).answer()).isEqualTo("完整答案");
        assertThat(rows.get(1).result().metadata().generationComplete()).isFalse();
        assertThat(rows.get(2).errorMessage()).hasSize(500);
        assertThat(service.context(id, 1)).contains("完整答案").doesNotContain("不完整答案", "失败问题");
    }

    @Test
    void interruptedExpiredTurnCanBeRetriedAndHistoryContextRemainsBounded() {
        String id = service.create(1, "过期").id();
        long old = service.begin(id, 1, "过期问题");
        jdbc.update("UPDATE rag_conversation_turn SET create_time=? WHERE id=?",
                LocalDateTime.now().minusMinutes(16), old);
        assertThat(service.turns(id, 1, null).records().get(0).status()).isEqualTo("INTERRUPTED");
        long next = service.begin(id, 1, "新问题");
        service.complete(id, 1, old, answer("过期回调", true));
        service.complete(id, 1, next, answer("新答案", true));
        for (int index = 0; index < 7; index++) {
            long turn = service.begin(id, 1, "问".repeat(2000));
            service.complete(id, 1, turn, answer("答".repeat(4000), true));
        }
        assertThat(service.context(id, 1)).hasSizeLessThanOrEqualTo(12000).doesNotContain("过期回调");
    }

    private RagService.Answer answer(String text, boolean complete) {
        var metadata = new RagService.AnswerMetadata("hybrid", true, 2, 1, 30,
                !complete, complete ? null : "LLM_INCOMPLETE", complete, complete ? "stop" : "length", 0);
        return new RagService.Answer(text, List.of(), "test", "test", 10, 20, 30, metadata);
    }

    private void login(long userId) {
        OpsPrincipal principal = new OpsPrincipal(userId, "account-" + userId, "test", List.of("ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private void assertNotFound(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
