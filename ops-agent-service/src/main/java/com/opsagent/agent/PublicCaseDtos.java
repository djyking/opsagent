package com.opsagent.agent;

import java.util.List;

/**
 * 公开案例的固定脱敏投影，不含原始工单、运行、审批或身份字段。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class PublicCaseDtos {
    private PublicCaseDtos() {}

    record CaseView(
            String id,
            String title,
            String scenarioCode,
            String targetCode,
            String environment,
            String recordedAt,
            String summary,
            String result,
            String confirmation,
            String source,
            List<String> evidence,
            List<String> steps,
            String limitation) {}
}
