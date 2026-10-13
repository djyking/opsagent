package com.opsagent.platform;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Coalesces the same observation window without holding a lock during remote I/O. Cache residence
 * starts at completion; source sample timestamps remain untouched.
 *
 * @author heyu
 * @since 2026/9/3
 */
final class ObservationSnapshotCache<T> {
    private final ConcurrentHashMap<String, Entry<T>> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<T>> pending =
            new ConcurrentHashMap<>();
    private final long lifetime;
    private final LongSupplier clock;

    ObservationSnapshotCache(Duration lifetime) {
        this(lifetime, System::nanoTime);
    }

    ObservationSnapshotCache(Duration lifetime, LongSupplier clock) {
        this.lifetime = lifetime.toNanos();
        this.clock = clock;
    }

    T get(String key, Supplier<T> loader) {
        Entry<T> previous = values.get(key);
        if (fresh(previous)) return previous.value();
        CompletableFuture<T> own = new CompletableFuture<>();
        CompletableFuture<T> existing = pending.putIfAbsent(key, own);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                throw failure;
            }
        }
        try {
            // Another reader may have completed between the initial cache read and our claim.
            previous = values.get(key);
            T result;
            if (fresh(previous)) result = previous.value();
            else {
                result = loader.get();
                values.put(key, new Entry<>(result, clock.getAsLong() + lifetime));
            }
            own.complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            own.completeExceptionally(failure);
            throw failure;
        } finally {
            pending.remove(key, own);
        }
    }

    private boolean fresh(Entry<T> entry) {
        return entry != null && entry.expiresAt() - clock.getAsLong() > 0;
    }

    private record Entry<T>(T value, long expiresAt) {}
}
