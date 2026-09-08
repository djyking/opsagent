package com.opsagent.rag;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

/**
 * 将传输异常归为固定原因码，不保存可能包含地址、凭据或响应正文的异常消息。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AiTransportDiagnostics {
    private AiTransportDiagnostics() {}

    static String causeTypes(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var types = new ArrayList<String>();
        for (Throwable cause = failure;
                cause != null && types.size() < 8 && visited.add(cause);
                cause = cause.getCause()) {
            // Class names only: exception messages may contain URLs, headers or response data.
            String type = cause.getClass().getName();
            types.add(type.matches("[A-Za-z0-9_.$]{1,160}") ? type : "UNAVAILABLE_TYPE");
        }
        return String.join(" -> ", types);
    }

    static String code(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        String fallback = "IO_FAILURE";
        for (Throwable cause = failure;
                cause != null && visited.add(cause);
                cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) return "CONNECT_TIMEOUT";
            if (cause instanceof HttpTimeoutException) return "RESPONSE_TIMEOUT";
            if (cause instanceof UnknownHostException
                    || cause instanceof UnresolvedAddressException) return "DNS_FAILURE";
            if (cause instanceof SSLHandshakeException) return "TLS_HANDSHAKE";
            if (cause instanceof SSLException) return "TLS_FAILURE";
            if (cause instanceof EOFException) return "CONNECTION_CLOSED";
            if (cause instanceof ConnectException) fallback = "CONNECT_FAILURE";
            else if (cause instanceof SocketException) fallback = "SOCKET_FAILURE";
            // JDK HTTP/2 resets are IOExceptions; inspect locally and retain only the fixed
            // category.
            String message = cause.getMessage();
            if (message != null
                    && (message.contains("RST_STREAM")
                            || message.contains("GOAWAY")
                            || message.contains("Stream cancelled"))) return "HTTP2_STREAM_CLOSED";
            if (message != null
                    && (message.contains("header parser received no bytes")
                            || message.contains("connection closed")
                            || message.contains("Connection reset"))) {
                fallback = "CONNECTION_CLOSED";
            }
        }
        return fallback;
    }
}
