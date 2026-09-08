package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.concurrent.CompletionException;

/**
 * 简单检查固定传输原因分类，不向模型发送请求。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AiTransportDiagnosticsTest {
    @Test
    void distinguishesTimeoutAndClosedConnectionThroughWrappers() {
        assertThat(
                        AiTransportDiagnostics.code(
                                new CompletionException(
                                        new HttpConnectTimeoutException("private"))))
                .isEqualTo("CONNECT_TIMEOUT");
        assertThat(
                        AiTransportDiagnostics.code(
                                new IOException("private", new EOFException("private"))))
                .isEqualTo("CONNECTION_CLOSED");
        assertThat(AiTransportDiagnostics.code(new ConnectException("private")))
                .isEqualTo("CONNECT_FAILURE");
    }

    @Test
    void retainsOnlyFixedCategoryForHttp2ResetOrUnknownFailure() {
        assertThat(AiTransportDiagnostics.code(new IOException("RST_STREAM with private response")))
                .isEqualTo("HTTP2_STREAM_CLOSED");
        assertThat(
                        AiTransportDiagnostics.code(
                                new IOException("Bearer private-token https://private.invalid")))
                .isEqualTo("IO_FAILURE");
        var failure =
                new CompletionException(
                        new IOException(
                                "Authorization: Bearer private-token https://private.invalid",
                                new EOFException("private response body")));
        assertThat(AiTransportDiagnostics.causeTypes(failure))
                .isEqualTo(
                        "java.util.concurrent.CompletionException -> java.io.IOException ->"
                                + " java.io.EOFException")
                .doesNotContain(
                        "private-token", "private.invalid", "Authorization", "response body");
    }
}
