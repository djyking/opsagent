package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;

import org.springframework.stereotype.Component;

/**
 * 使用 Sentinel 对同步和流式问答入口执行统一 QPS 限流。
 *
 * @author heyu
 * @since 2026/9/2
 */
@Component
public class RagRateLimiter {
    static final String RESOURCE = "ops-rag-ask";
    private final AiBudgetGuard budget;

    RagRateLimiter(AiBudgetGuard budget) {
        this.budget = budget;
    }

    void check() {
        budget.checkRequestRate();
        Entry entry = null;
        try {
            entry = SphU.entry(RESOURCE);
        } catch (BlockException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "问答请求过于频繁，请稍后再试");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }
}
