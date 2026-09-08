package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 验证实际出站请求保留九十秒配置期限，完整响应体仍由有界调用接收。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AiHttpExecutorDeadlineTest {
    @Test
    void ninetySecondDeadlineIsNotSilentlyReducedBeforeSendingHttp() {
        var http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.version()).thenReturn(HttpClient.Version.HTTP_1_1);
        when(response.body()).thenReturn("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        when(http.sendAsync(
                        any(),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        var executor = new AiHttpExecutor();
        ReflectionTestUtils.setField(executor, "boundedClient", http);
        assertThat(
                        executor.postBounded(
                                        "deepseek",
                                        "http://localhost",
                                        "/chat/completions",
                                        "test-key",
                                        Map.of("model", "test-model"),
                                        Duration.ofSeconds(90))
                                .path("ok")
                                .asBoolean())
                .isTrue();
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http)
                .sendAsync(
                        request.capture(),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(90));
    }
}
