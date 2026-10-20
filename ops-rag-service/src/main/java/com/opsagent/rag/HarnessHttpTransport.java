package com.opsagent.rag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Single-attempt transport whose deadline and byte limit cover the entire response body.
 *
 * @author heyu
 * @since 2026/9/3
 */
final class HarnessHttpTransport {
    private HarnessHttpTransport() {}

    static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int maxBytes)
            throws IOException, InterruptedException {
        if (maxBytes <= 0) throw new IllegalArgumentException("Positive response limit required");
        var timeout = request.timeout().orElseThrow();
        var pending = client.sendAsync(request, info -> new BoundedBody(maxBytes));
        try {
            return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeoutFailure) {
            throw new HttpTimeoutException("HARNESS_RESPONSE_DEADLINE_EXCEEDED");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw new IOException("HARNESS_RESPONSE_UNAVAILABLE", failure.getCause());
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    /**
     * @author heyu
     * @since 2026/9/3
     */
    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException() {
            super("HARNESS_RESPONSE_TOO_LARGE");
        }
    }

    /**
     * Counts actual bytes, including chunked responses without a Content-Length.
     *
     * @author heyu
     * @since 2026/9/3
     */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        private BoundedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] next = new byte[buffer.remaining()];
                buffer.get(next);
                bytes.writeBytes(next);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
