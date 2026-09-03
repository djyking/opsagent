package com.opsagent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 将不含问题正文和密钥的 AI 用量审计写入独立数据库。
 *
 * @author heyu
 * @since 2026/9/1
 */
@Repository
public class AiUsageRepository {
    private static final Logger LOG = LoggerFactory.getLogger(AiUsageRepository.class);
    private final JdbcTemplate jdbc;

    AiUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void save(AiUsage usage) {
        try {
            jdbc.update(
                    "INSERT INTO ai_usage_log(trace_id,user_id,provider,model,question_hash,"
                            + "input_tokens,output_tokens,latency_ms,success,error_code,create_time)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,NOW())",
                    usage.traceId(),
                    usage.userId(),
                    usage.provider(),
                    usage.model(),
                    usage.questionHash(),
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.latencyMs(),
                    usage.success(),
                    usage.errorCode());
        } catch (DataAccessException exception) {
            LOG.warn("AI 用量审计写入失败，provider={}，不影响本次回答", usage.provider());
        }
    }

    /**
     * 表示一条经过最小化处理的模型调用审计记录。
     *
     * @author heyu
     * @since 2026/9/1
     */
    record AiUsage(
            String traceId,
            long userId,
            String provider,
            String model,
            String questionHash,
            int inputTokens,
            int outputTokens,
            long latencyMs,
            boolean success,
            String errorCode) {}
}
