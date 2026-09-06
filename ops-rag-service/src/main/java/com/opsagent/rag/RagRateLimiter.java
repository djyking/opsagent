package com.opsagent.rag;

import com.alibaba.csp.sentinel.AsyncEntry;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用 Sentinel 对同步和流式问答入口执行统一 QPS 限流。
 *
 * @author heyu
 * @since 2026/9/2
 */
@Component
public class RagRateLimiter {
    static final String RESOURCE = "ops-rag-ask";
    static final String REQUEST_RESOURCE = "ops-rag-request";
    private final AiBudgetGuard budget;
    private final Counter passed;
    private final Counter blocked;

    RagRateLimiter(AiBudgetGuard budget, MeterRegistry metrics) {
        this.budget = budget;
        this.passed = metrics.counter("opsagent.rag.sentinel.passed");
        this.blocked = metrics.counter("opsagent.rag.sentinel.blocked");
    }

    void check() {
        budget.checkRequestRate();
        Entry entry = null;
        try {
            entry = SphU.entry(RESOURCE);
            passed.increment();
        } catch (BlockException exception) {
            blocked.increment();
            throw new BusinessException(ErrorCode.VALIDATION, "问答请求过于频繁，请稍后再试");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    Scope requestScope() {
        try {
            return new Scope(SphU.asyncEntry(REQUEST_RESOURCE, EntryType.IN));
        } catch (BlockException exception) {
            blocked.increment();
            throw new BusinessException(ErrorCode.VALIDATION, "问答入口正处于流量保护，请稍后再试");
        }
    }

    /**
     * Async entry owns actual request lifetime, including streaming completion.
     *
     * @author heyu
     * @since 2026/9/3
     */
    static final class Scope implements AutoCloseable {
        private final AsyncEntry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        Scope(AsyncEntry entry) {
            this.entry = entry;
        }

        void failure(Throwable cause) {
            if (closed.compareAndSet(false, true)) {
                Tracer.traceEntry(cause, entry);
                entry.exit();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) entry.exit();
        }
    }
}
