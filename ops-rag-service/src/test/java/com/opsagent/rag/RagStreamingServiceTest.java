package com.opsagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.AsyncEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 SSE 只有在结果保存成功后发送 done，且超时任务不会晚到覆盖失败状态。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RagStreamingServiceTest {
    private final RagService rag = mock(RagService.class);
    private final AiProperties properties = new AiProperties();
    private final RagService.Answer answer = new RagService.Answer(
            "保留未完成的句子", List.of(), "deepseek", "model", 1, 2, 3,
            new RagService.AnswerMetadata("NONE", false, 0, 0, 0, true, "LLM_INCOMPLETE",
                    false, "length", 2));
    private final RagService.StreamPlan plan = RagService.StreamPlan.completed("问题", answer, 0);
    private final LlmInvocationService.AuditContext context =
            new LlmInvocationService.AuditContext(1, "test");

    @Test
    void shouldPersistPartialAnswerBeforeDeliveringExplicitIncompleteDone() throws Exception {
        List<RagService.Answer> saved = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        stubAnswer();
        MockMvc mvc = mvc(Runnable::run, saved::add, errors::add);

        MvcResult started = mvc.perform(get("/test-stream"))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(saved).containsExactly(answer);
        assertThat(errors).isEmpty();
        assertThat(body).contains("event:done", "\"generationComplete\":false", "\"finishReason\":\"length\"");
    }

    @Test
    void shouldNotSendDoneIfSavingResultFails() throws Exception {
        List<String> errors = new ArrayList<>();
        stubAnswer();
        MockMvc mvc = mvc(Runnable::run, value -> { throw new IllegalStateException("storage failure"); },
                errors::add);

        MvcResult started = mvc.perform(get("/test-stream")).andReturn();
        String body = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:error").doesNotContain("event:done");
        assertThat(errors).hasSize(1);
    }

    @Test
    void shouldCancelQueuedGenerationAfterServletTimeout() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        List<RagService.Answer> saved = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        MockMvc mvc = mvc(queued::add, saved::add, errors::add);
        MvcResult started = mvc.perform(get("/test-stream"))
                .andExpect(request().asyncStarted()).andReturn();
        MockAsyncContext async = (MockAsyncContext) started.getRequest().getAsyncContext();

        for (var listener : async.getListeners()) listener.onTimeout(new AsyncEvent(async));
        queued.forEach(Runnable::run);

        assertThat(saved).isEmpty();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("超时");
        verifyNoInteractions(rag);
    }

    @Test
    void shouldBudgetAllProviderAttemptsAndContinuations() {
        properties.setTimeoutSeconds(90);
        properties.setMaximumAttempts(3);
        properties.setMaximumContinuations(2);
        assertThat(properties.streamTimeoutMillis()).isEqualTo(846000L);
    }

    private void stubAnswer() {
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(1);
            delta.accept(answer.answer());
            return answer;
        }).when(rag).stream(eq(plan), any(), eq(context));
    }

    private MockMvc mvc(
            Executor executor, Consumer<RagService.Answer> save, Consumer<String> error) {
        RagStreamingService streaming = new RagStreamingService(rag, properties, executor);
        return MockMvcBuilders.standaloneSetup(new StreamController(
                () -> streaming.open(plan, context, save, error))).build();
    }

    /**
     * 提供真实 MVC 的 SSE 响应装配环境。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @RestController
    static final class StreamController {
        private final java.util.function.Supplier<SseEmitter> stream;

        StreamController(java.util.function.Supplier<SseEmitter> stream) { this.stream = stream; }

        @GetMapping(value = "/test-stream", produces = "text/event-stream")
        SseEmitter open() { return stream.get(); }
    }
}

