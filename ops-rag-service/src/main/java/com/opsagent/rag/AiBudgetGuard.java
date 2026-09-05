package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 公网单实例演示的生成准入：限制运行中生成、滚动分钟频率和持久化 UTC 每日额度。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class AiBudgetGuard {
    private final JdbcTemplate jdbc;
    private final RagProperties rag;
    private final boolean enabled;
    private final int maximumConcurrent;
    private final int requestsPerDay;
    private final Clock clock;
    private final Deque<Long> recentStarts = new ArrayDeque<>();
    private final Deque<Long> recentRequests = new ArrayDeque<>();
    private int active;

    @Autowired
    AiBudgetGuard(JdbcTemplate jdbc, RagProperties rag,
            @Value("${ops.ai.budget.enabled:false}") boolean enabled,
            @Value("${ops.ai.budget.maximum-concurrent:2}") int maximumConcurrent,
            @Value("${ops.ai.budget.requests-per-day:100}") int requestsPerDay) {
        this(jdbc, rag, enabled, maximumConcurrent, requestsPerDay, Clock.systemUTC());
    }

    AiBudgetGuard(JdbcTemplate jdbc, RagProperties rag, boolean enabled,
            int maximumConcurrent, int requestsPerDay, Clock clock) {
        this.jdbc = jdbc;
        this.rag = rag;
        this.enabled = enabled;
        this.maximumConcurrent = maximumConcurrent;
        this.requestsPerDay = requestsPerDay;
        this.clock = clock;
    }

    @PostConstruct
    void initialize() {
        if (!enabled) return;
        if (maximumConcurrent < 1 || requestsPerDay < 1 || rag.getRequestsPerMinute() < 1) {
            throw new IllegalArgumentException("公网 AI 并发、分钟和每日预算必须为正数");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS rag_ai_daily_budget ("
                + "budget_day DATE PRIMARY KEY, request_count INTEGER NOT NULL)");
    }

    synchronized Permit acquire() {
        if (!enabled) return () -> {};
        long now = clock.millis();
        while (!recentStarts.isEmpty() && recentStarts.peekFirst() <= now - 60_000L) recentStarts.removeFirst();
        if (active >= maximumConcurrent) {
            throw rejected("当前 AI 正在处理其他问题，请稍后重试。");
        }
        if (recentStarts.size() >= rag.getRequestsPerMinute()) {
            throw rejected("AI 问答请求过于频繁，请一分钟后重试。");
        }
        LocalDate day = LocalDate.now(clock);
        try {
            jdbc.update("INSERT INTO rag_ai_daily_budget(budget_day,request_count) VALUES(?,0)"
                    + " ON DUPLICATE KEY UPDATE budget_day=budget_day", day);
            int reserved = jdbc.update("UPDATE rag_ai_daily_budget SET request_count=request_count+1"
                    + " WHERE budget_day=? AND request_count<?", day, requestsPerDay);
            if (reserved == 0) throw rejected("今日 AI 演示额度已用完，请明日再试。");
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "暂时无法核实 AI 额度，请稍后重试。");
        }
        recentStarts.addLast(now);
        active++;
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) release();
        };
    }

    synchronized void checkRequestRate() {
        if (!enabled) return;
        long now = clock.millis();
        while (!recentRequests.isEmpty() && recentRequests.peekFirst() <= now - 60_000L) recentRequests.removeFirst();
        if (recentRequests.size() >= rag.getRequestsPerMinute()) {
            throw rejected("问答请求过于频繁，请一分钟后重试。");
        }
        // Check before retrieval as well, so exhausted budgets cannot keep issuing query embeddings.
        try {
            Integer used = jdbc.queryForObject("SELECT COALESCE(SUM(request_count),0) FROM rag_ai_daily_budget"
                    + " WHERE budget_day=?", Integer.class, LocalDate.now(clock));
            if (used != null && used >= requestsPerDay) {
                throw rejected("今日 AI 演示额度已用完，请明日再试。");
            }
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "暂时无法核实 AI 额度，请稍后重试。");
        }
        recentRequests.addLast(now);
    }

    private synchronized void release() {
        active--;
    }

    private BusinessException rejected(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    /**
     * 覆盖完整同步或流式生成生命周期，异常和客户端断开时也释放运行名额。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @FunctionalInterface
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
