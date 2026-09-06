package com.opsagent.platform;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.core.PageResult;
import com.opsagent.common.security.OpsPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 持久保存工作流运行、步骤和只追加审计，每次状态转换独立提交以保留中断证据。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
public class OperationsWorkflowRepository {
    private static final RowMapper<OperationsDtos.Run> RUN_MAPPER =
            (row, index) ->
                    new OperationsDtos.Run(
                            row.getLong("id"),
                            row.getString("workflow_code"),
                            row.getString("title"),
                            row.getString("mode"),
                            row.getString("status"),
                            row.getString("summary"),
                            time(row, "started_at"),
                            time(row, "finished_at"),
                            row.getString("actor"));
    private final JdbcTemplate jdbc;

    OperationsWorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long start(OperationsDtos.Workflow workflow, OpsPrincipal actor) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "INSERT INTO"
                                        + " operations_workflow_run(workflow_code,title,mode,status,actor_id,actor)"
                                        + " VALUES(?,?,?,'RUNNING',?,?)",
                                    new String[] {"id"});
                    statement.setString(1, workflow.code());
                    statement.setString(2, workflow.title());
                    statement.setString(3, workflow.mode());
                    statement.setLong(4, actor.userId());
                    statement.setString(5, actor.username());
                    return statement;
                },
                key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    void startStep(long runId, int sequence, String title) {
        jdbc.update(
                "INSERT INTO operations_workflow_step(run_id,step_sequence,title,status)"
                    + " VALUES(?,?,?,'RUNNING')",
                runId,
                sequence,
                title);
    }

    void finishStep(long runId, int sequence, String status, String detail, String evidence) {
        jdbc.update(
                "UPDATE operations_workflow_step SET"
                    + " status=?,detail=?,evidence=?,finished_at=CURRENT_TIMESTAMP(3) WHERE"
                    + " run_id=? AND step_sequence=? AND status='RUNNING'",
                status,
                limit(detail, 2000),
                limit(evidence, 6000),
                runId,
                sequence);
    }

    void finish(long runId, String status, String summary) {
        jdbc.update(
                "UPDATE operations_workflow_run SET"
                    + " status=?,summary=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=? AND"
                    + " status='RUNNING'",
                status,
                limit(summary, 1500),
                runId);
    }

    void audit(long runId, String action, String detail, String actor) {
        jdbc.update(
                "INSERT INTO operations_workflow_audit(run_id,action,detail,actor) VALUES(?,?,?,?)",
                runId,
                action,
                limit(detail, 2000),
                actor);
    }

    PageResult<OperationsDtos.Run> page(int requestedPage, int requestedSize) {
        int page = Math.max(1, requestedPage);
        int size = Math.max(1, Math.min(50, requestedSize));
        Long total =
                jdbc.queryForObject("SELECT COUNT(*) FROM operations_workflow_run", Long.class);
        List<OperationsDtos.Run> rows =
                jdbc.query(
                        "SELECT * FROM operations_workflow_run ORDER BY id DESC"
                                + " LIMIT ? OFFSET ?",
                        RUN_MAPPER,
                        size,
                        (page - 1L) * size);
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    Instant lastRunAt(String workflowCode) {
        Timestamp value =
                jdbc.queryForObject(
                        "SELECT MAX(started_at) FROM operations_workflow_run"
                                + " WHERE workflow_code=?",
                        Timestamp.class,
                        workflowCode);
        return value == null ? null : value.toInstant();
    }

    OperationsDtos.RunDetail detail(long id) {
        List<OperationsDtos.Run> runs =
                jdbc.query("SELECT * FROM operations_workflow_run WHERE id=?", RUN_MAPPER, id);
        if (runs.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "工作流记录不存在");
        List<OperationsDtos.Step> steps =
                jdbc.query(
                        "SELECT * FROM operations_workflow_step WHERE run_id=? ORDER BY"
                            + " step_sequence",
                        (row, index) ->
                                new OperationsDtos.Step(
                                        row.getLong("id"),
                                        row.getInt("step_sequence"),
                                        row.getString("title"),
                                        row.getString("status"),
                                        row.getString("detail"),
                                        row.getString("evidence"),
                                        time(row, "started_at"),
                                        time(row, "finished_at")),
                        id);
        List<OperationsDtos.Audit> audits =
                jdbc.query(
                        "SELECT * FROM operations_workflow_audit WHERE run_id=? ORDER BY id",
                        (row, index) ->
                                new OperationsDtos.Audit(
                                        row.getString("action"),
                                        row.getString("detail"),
                                        time(row, "created_at"),
                                        row.getString("actor")),
                        id);
        return OperationsDtos.RunDetail.from(runs.get(0), steps, audits);
    }

    void interruptUnfinished() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(600));
        jdbc.update(
                "INSERT INTO operations_workflow_audit(run_id,action,detail,actor)"
                        + " SELECT id,'INTERRUPTED','执行进程中断；隔离实验容器会按TTL自动恢复，需重新验证。','system'"
                        + " FROM operations_workflow_run WHERE status='RUNNING' AND started_at<?",
                cutoff);
        jdbc.update(
                "UPDATE operations_workflow_step SET"
                    + " status='FAILED',detail='执行进程中断，未获得完成证据',finished_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE status='RUNNING' AND run_id IN (SELECT id FROM"
                    + " operations_workflow_run WHERE status='RUNNING' AND started_at<?)",
                cutoff);
        jdbc.update(
                "UPDATE operations_workflow_run SET"
                    + " status='FAILED',summary='进程中断，未自动续跑或重复注入故障',finished_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE status='RUNNING' AND started_at<?",
                cutoff);
    }

    void interruptExpiredInspections(List<Long> runIds) {
        for (Long runId : runIds) {
            jdbc.update(
                    "UPDATE operations_workflow_step SET"
                        + " status='FAILED',detail='巡检租约到期，未取得完成证据',finished_at=CURRENT_TIMESTAMP(3)"
                        + " WHERE run_id=? AND status='RUNNING'",
                    runId);
            int changed =
                    jdbc.update(
                            "UPDATE operations_workflow_run SET status='FAILED',"
                                + "summary='巡检执行租约已到期；保留证据，下个时隙可重新执行',finished_at=CURRENT_TIMESTAMP(3)"
                                + " WHERE id=? AND status='RUNNING'",
                            runId);
            if (changed > 0) audit(runId, "LEASE_EXPIRED", "运行租约过期，未将未知检查标记成功", "system-health");
        }
    }

    private static Instant time(ResultSet row, String field) throws SQLException {
        Timestamp value = row.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private String limit(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
