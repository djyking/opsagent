package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import jakarta.annotation.PostConstruct;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * 工作流版本、执行检查点与审批的事务存储。HTTP 不在数据库事务内执行。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
class AgentStore {
    private static final int MAX_ACTIVE_RUNS = 8;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    AgentStore(
            JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }

    @PostConstruct
    void initialize() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("数据库不可用");
        new ResourceDatabasePopulator(new ClassPathResource("agent-schema.sql")).execute(source);
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_definition WHERE id='isolated-recovery'",
                        Long.class)
                == 0) {
            ObjectNode graph = WorkflowGraph.builtin();
            tx.executeWithoutResult(
                    status -> {
                        jdbc.update(
                                "INSERT INTO agent_definition(id,name,draft_json,published_version)"
                                        + " VALUES(?,?,?,1)",
                                "isolated-recovery",
                                "隔离业务故障诊断与恢复",
                                graph.toString());
                        jdbc.update(
                                "INSERT INTO"
                                    + " agent_version(definition_id,version,snapshot_json,snapshot_hash)"
                                    + " VALUES(?,1,?,?)",
                                "isolated-recovery",
                                graph.toString(),
                                AgentJson.hash(graph));
                    });
        }
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_definition WHERE id='configuration-change'",
                        Long.class)
                == 0) {
            ObjectNode graph = WorkflowGraph.configurationChange();
            tx.executeWithoutResult(
                    status -> {
                        jdbc.update(
                                "INSERT INTO agent_definition(id,name,draft_json,published_version)"
                                    + " VALUES(?,?,?,1)",
                                "configuration-change",
                                "受控配置变更与应用核验",
                                graph.toString());
                        jdbc.update(
                                "INSERT INTO"
                                    + " agent_version(definition_id,version,snapshot_json,snapshot_hash)"
                                    + " VALUES(?,1,?,?)",
                                "configuration-change",
                                graph.toString(),
                                AgentJson.hash(graph));
                    });
        }
    }

    List<Map<String, Object>> definitions() {
        return jdbc.queryForList(
                "SELECT id,name,draft_revision,published_version,updated_at FROM agent_definition");
    }

    JsonNode definition(String id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM agent_definition WHERE id=?", id);
        if (rows.isEmpty()) throw missing();
        ObjectNode view = (ObjectNode) AgentJson.tree(rows.get(0));
        view.set("graph", AgentJson.read(view.remove("draft_json").asText()));
        return view;
    }

    void draft(String id, String name, JsonNode graph, int revision) {
        WorkflowGraph.validate(graph);
        if (!id.matches("[a-z][a-z0-9-]{0,60}") || name.isBlank() || name.length() > 120) {
            throw AgentJson.invalid("定义名称或 ID 无效");
        }
        if (revision == 0) {
            jdbc.update(
                    "INSERT INTO agent_definition(id,name,draft_json) VALUES(?,?,?)",
                    id,
                    name,
                    graph.toString());
        } else if (jdbc.update(
                        """
                        UPDATE agent_definition SET name=?,draft_json=?,
                        draft_revision=draft_revision+1,updated_at=NOW(3)
                        WHERE id=? AND draft_revision=?
                        """,
                        name,
                        graph.toString(),
                        id,
                        revision)
                != 1) throw conflict();
    }

    int publish(String id, int revision) {
        return tx.execute(
                status -> {
                    Map<String, Object> row =
                            jdbc.queryForMap(
                                    "SELECT * FROM agent_definition WHERE id=? FOR UPDATE", id);
                    if (((Number) row.get("draft_revision")).intValue() != revision)
                        throw conflict();
                    JsonNode graph = AgentJson.read((String) row.get("draft_json"));
                    WorkflowGraph.validate(graph);
                    int version = ((Number) row.get("published_version")).intValue() + 1;
                    jdbc.update(
                            "INSERT INTO"
                                + " agent_version(definition_id,version,snapshot_json,snapshot_hash)"
                                + " VALUES(?,?,?,?)",
                            id,
                            version,
                            graph.toString(),
                            AgentJson.hash(graph));
                    jdbc.update(
                            "UPDATE agent_definition SET published_version=?,updated_at=NOW(3)"
                                    + " WHERE id=?",
                            version,
                            id);
                    return version;
                });
    }

    JsonNode version(String id) {
        List<String> values =
                jdbc.queryForList(
                        """
                        SELECT v.snapshot_json FROM agent_version v JOIN agent_definition d
                        ON v.definition_id=d.id AND v.version=d.published_version WHERE d.id=?
                        """,
                        String.class,
                        id);
        if (values.isEmpty()) throw AgentJson.invalid("请先发布工作流");
        return AgentJson.read(values.get(0));
    }

    String create(
            String definition, String trigger, long owner, ObjectNode snapshot, ObjectNode state) {
        return tx.execute(
                status -> {
                    jdbc.queryForObject(
                            "SELECT id FROM agent_runtime_guard WHERE id=1 FOR UPDATE",
                            Integer.class);
                    List<String> existing =
                            jdbc.queryForList(
                                    "SELECT id FROM agent_run WHERE trigger_key=?",
                                    String.class,
                                    trigger);
                    if (!existing.isEmpty()) {
                        Run previous = get(existing.get(0));
                        String previousDefinition =
                                jdbc.queryForObject(
                                        "SELECT definition_id FROM agent_run WHERE id=?",
                                        String.class,
                                        previous.id());
                        if (previous.owner() != owner
                                || !definition.equals(previousDefinition)
                                || previous.state().path("ticketId").asLong()
                                        != state.path("ticketId").asLong()
                                || !previous.snapshot()
                                        .path("model")
                                        .path("provider")
                                        .asText()
                                        .equalsIgnoreCase(
                                                snapshot.path("model").path("provider").asText()))
                            throw conflict();
                        if (!previous.snapshot()
                                .path("configurationProposal")
                                .path("immutableDigest")
                                .equals(
                                        snapshot.path("configurationProposal")
                                                .path("immutableDigest"))) throw conflict();
                        return previous.id();
                    }
                    long active =
                            jdbc.queryForObject(
                                    """
SELECT COUNT(*) FROM agent_run
WHERE status IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_INPUT')
""",
                                    Long.class);
                    if (active >= MAX_ACTIVE_RUNS)
                        throw AgentJson.invalid("当前运行数已达演示环境上限，请先完成或取消已有运行");
                    int version =
                            jdbc.queryForObject(
                                    "SELECT published_version FROM agent_definition WHERE id=? FOR"
                                            + " UPDATE",
                                    Integer.class,
                                    definition);
                    String graph =
                            jdbc.queryForObject(
                                    "SELECT snapshot_json FROM agent_version"
                                            + " WHERE definition_id=? AND version=?",
                                    String.class,
                                    definition,
                                    version);
                    ObjectNode frozen = snapshot.deepCopy();
                    frozen.set("graph", AgentJson.read(graph));
                    frozen.remove("hash");
                    frozen.put("hash", AgentJson.hash(frozen));
                    String id = state.path("runId").asText();
                    jdbc.update(
                            """
                            INSERT INTO agent_run(id,trigger_key,definition_id,version,owner_id,
                            ticket_id,incident_id,status,node_id,
                            snapshot_json,state_json) VALUES(?,?,?,?,?,?,?,'QUEUED',?,?,?)
                            """,
                            id,
                            trigger,
                            definition,
                            version,
                            owner,
                            state.path("ticketId").asLong(),
                            state.path("incidentId").asText(),
                            WorkflowGraph.start(frozen.path("graph")),
                            frozen.toString(),
                            state.toString());
                    event(id, "RUN_CREATED", "", Map.of("version", version, "trigger", trigger));
                    wake(id);
                    return id;
                });
    }

    Run get(String id) {
        List<Run> rows = jdbc.query("SELECT * FROM agent_run WHERE id=?", AgentStore::mapRun, id);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    List<Map<String, Object>> runs(
            int page, int size, long owner, boolean all, Long ticketId, String incidentId) {
        String query =
                """
                SELECT id,definition_id,version,owner_id,ticket_id,incident_id,status,node_id,
                pause_requested,created_at,updated_at
                FROM agent_run
                """;
        List<Object> parameters = new ArrayList<>();
        String filtered = filters(owner, all, ticketId, incidentId, parameters);
        parameters.add(size);
        parameters.add((page - 1) * size);
        var rows =
                jdbc.queryForList(
                        query + filtered + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                        parameters.toArray());
        rows.forEach(
                row -> {
                    row.put("ticketId", row.get("ticket_id"));
                    row.put("incidentId", row.get("incident_id"));
                });
        return rows;
    }

    long count(long owner, boolean all, Long ticketId, String incidentId) {
        List<Object> parameters = new ArrayList<>();
        String filtered = filters(owner, all, ticketId, incidentId, parameters);
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run" + filtered, Long.class, parameters.toArray());
    }

    List<Run> workspaceRuns(long owner, boolean all, long ticketId, String incidentId) {
        List<Object> parameters = new ArrayList<>();
        String filtered = filters(owner, all, ticketId, incidentId, parameters);
        return jdbc.query(
                "SELECT * FROM agent_run" + filtered + " ORDER BY created_at DESC,id DESC LIMIT 6",
                AgentStore::mapRun,
                parameters.toArray());
    }

    Map<String, Long> statusCounts(long owner, boolean all) {
        List<Object> parameters = new ArrayList<>();
        String filtered = filters(owner, all, null, null, parameters);
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        jdbc.query(
                "SELECT status,COUNT(*) AS total FROM agent_run" + filtered + " GROUP BY status",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        row -> counts.put(row.getString("status"), row.getLong("total")),
                parameters.toArray());
        return counts;
    }

    private String filters(
            long owner, boolean all, Long ticket, String incident, List<Object> parameters) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        if (!all) {
            sql.append(" AND owner_id=?");
            parameters.add(owner);
        }
        if (ticket != null) {
            sql.append(" AND ticket_id=?");
            parameters.add(ticket);
        }
        if (incident != null && !incident.isBlank()) {
            sql.append(" AND incident_id=?");
            parameters.add(incident);
        }
        return sql.toString();
    }

    Run claim() {
        return tx.execute(
                status -> {
                    List<String> ids =
                            jdbc.queryForList(
                                    """
SELECT id FROM agent_run WHERE status IN ('QUEUED','RUNNING')
AND next_attempt<=NOW(3) AND (lease_until IS NULL OR lease_until<NOW(3))
ORDER BY next_attempt LIMIT 1 FOR UPDATE SKIP LOCKED
""",
                                    String.class);
                    if (ids.isEmpty()) return null;
                    String id = ids.get(0);
                    jdbc.update(
                            """
                            UPDATE agent_run SET status='RUNNING',fence=fence+1,
                            lease_until=TIMESTAMPADD(SECOND,120,NOW(3)) WHERE id=?
                            """,
                            id);
                    return get(id);
                });
    }

    void checkpoint(
            Run run, String status, String node, ObjectNode state, String event, Object payload) {
        checkpoint(run, status, node, state, event, payload, 1);
    }

    void checkpoint(
            Run run,
            String status,
            String node,
            ObjectNode state,
            String event,
            Object payload,
            int delaySeconds) {
        if (delaySeconds < 1 || delaySeconds > 120) throw AgentJson.invalid("检查点延迟超出范围");
        tx.executeWithoutResult(
                transaction -> {
                    int updated =
                            jdbc.update(
                                    """
UPDATE agent_run SET status=CASE WHEN cancel_requested=TRUE
AND ? IN ('QUEUED','WAITING_APPROVAL','WAITING_INPUT') THEN 'QUEUED'
WHEN pause_requested=TRUE AND ?='QUEUED' THEN 'PAUSED' ELSE ? END,
node_id=?,state_json=?,lease_until=NULL,
next_attempt=TIMESTAMPADD(SECOND,?,NOW(3)),updated_at=NOW(3)
WHERE id=? AND fence=? AND lease_until>NOW(3)
""",
                                    status,
                                    status,
                                    status,
                                    node,
                                    state.toString(),
                                    delaySeconds,
                                    run.id(),
                                    run.fence());
                    if (updated != 1) throw conflict();
                    if (!List.of("QUEUED", "PAUSED", "WAITING_APPROVAL", "WAITING_INPUT")
                            .contains(status)) {
                        jdbc.update(
                                "UPDATE agent_approval SET status=? WHERE run_id=? AND"
                                        + " status='PENDING'",
                                status.equals("EXPIRED") ? "EXPIRED" : "CANCELLED",
                                run.id());
                    }
                    event(run.id(), event, run.node(), payload);
                    if (status.equals("QUEUED") && delaySeconds == 1) wake(run.id());
                });
    }

    void event(String run, String type, String node, Object payload) {
        jdbc.update(
                "INSERT INTO agent_event(run_id,event_type,node_id,payload_json) VALUES(?,?,?,?)",
                run,
                type,
                node,
                AgentJson.tree(payload).toString());
    }

    List<JsonNode> events(String run, long after) {
        return jdbc.query(
                "SELECT * FROM agent_event WHERE run_id=? AND id>? ORDER BY id LIMIT 200",
                (rs, row) -> {
                    ObjectNode event =
                            AgentJson.object()
                                    .put("id", rs.getLong("id"))
                                    .put("type", rs.getString("event_type"))
                                    .put("nodeId", rs.getString("node_id"))
                                    .put(
                                            "createdAt",
                                            rs.getTimestamp("created_at").toInstant().toString());
                    event.set("payload", AgentJson.read(rs.getString("payload_json")));
                    return event;
                },
                run,
                after);
    }

    JsonNode approval(String run, String call) {
        List<String> ids =
                jdbc.queryForList(
                        "SELECT id FROM agent_approval WHERE run_id=? AND call_id=?",
                        String.class,
                        run,
                        call);
        return ids.isEmpty() ? null : approval(ids.get(0));
    }

    JsonNode approval(String id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM agent_approval WHERE id=?", id);
        if (rows.isEmpty()) throw missing();
        ObjectNode result = (ObjectNode) AgentJson.tree(rows.get(0));
        result.set("payload", AgentJson.read(result.remove("payload_json").asText()));
        return result;
    }

    Map<String, Object> pendingApprovals(int limit, long owner, boolean all) {
        if (limit < 1 || limit > 50) throw AgentJson.invalid("待审批数量范围应为 1 至 50");
        return tx.execute(
                transaction -> {
                    List<Object> scope = new ArrayList<>();
                    String ownerFilter = all ? "" : " AND owner_id=?";
                    if (!all) scope.add(owner);
                    scope.add(MAX_ACTIVE_RUNS + 1);
                    List<Run> candidates =
                            jdbc.query(
                                    "SELECT * FROM agent_run WHERE status IN"
                                            + " ('WAITING_APPROVAL','WAITING_INPUT') AND"
                                            + " cancel_requested=FALSE AND pause_requested=FALSE"
                                            + ownerFilter
                                            + " ORDER BY created_at,id LIMIT ?",
                                    AgentStore::mapRun,
                                    scope.toArray());
                    // The same hard limit is enforced by create under the runtime guard lock.
                    // Fail closed if imported data violates it, rather than report a partial total.
                    if (candidates.size() > MAX_ACTIVE_RUNS) throw conflict();
                    Instant now = Instant.now();
                    Map<String, Run> active = new HashMap<>();
                    List<String> calls = new ArrayList<>();
                    List<Object> parameters = new ArrayList<>();
                    parameters.add(Timestamp.from(now));
                    for (Run run : candidates) {
                        String callId = run.state().path("toolIntent").path("id").asText();
                        if (!approvalRunActive(run, now) || callId.isBlank()) continue;
                        active.put(run.id(), run);
                        calls.add("(a.run_id=? AND a.call_id=? AND a.args_hash=?)");
                        parameters.add(run.id());
                        parameters.add(callId);
                        parameters.add(AgentJson.hash(run.state().path("toolIntent")));
                    }
                    if (active.isEmpty()) return Map.of("items", List.of(), "total", 0L);
                    parameters.add(MAX_ACTIVE_RUNS);
                    List<JsonNode> approvals =
                            jdbc.query(
                                    "SELECT a.* FROM agent_approval a JOIN agent_run r ON"
                                        + " r.id=a.run_id WHERE a.status='PENDING' AND"
                                        + " a.expires_at>? AND r.status IN"
                                        + " ('WAITING_APPROVAL','WAITING_INPUT') AND"
                                        + " r.cancel_requested=FALSE AND r.pause_requested=FALSE"
                                        + " AND ("
                                            + String.join(" OR ", calls)
                                            + ") ORDER BY a.expires_at,a.created_at,a.id LIMIT ?",
                                    (rs, row) ->
                                            pendingApproval(rs, active.get(rs.getString("run_id"))),
                                    parameters.toArray());
                    return Map.of(
                            "items",
                            List.copyOf(approvals.subList(0, Math.min(limit, approvals.size()))),
                            "total",
                            (long) approvals.size());
                });
    }

    private static JsonNode pendingApproval(ResultSet rs, Run run) throws SQLException {
        String nodeLabel = run.node();
        for (JsonNode node : run.snapshot().path("graph").path("nodes")) {
            if (node.path("id").asText().equals(run.node())) {
                nodeLabel = node.path("label").asText(run.node());
                break;
            }
        }
        ObjectNode result =
                AgentJson.object()
                        .put("id", rs.getString("id"))
                        .put("run_id", run.id())
                        .put("status", rs.getString("status"))
                        .put("args_hash", rs.getString("args_hash"))
                        .put("revision", rs.getInt("revision"))
                        .put("expires_at", rs.getTimestamp("expires_at").toInstant().toString())
                        .put("runStatus", run.status())
                        .put("ownerId", run.owner())
                        .put("ticketId", run.state().path("ticketId").asLong())
                        .put("incidentId", run.state().path("incidentId").asText())
                        .put("nodeId", run.node())
                        .put("nodeLabel", nodeLabel)
                        .put("runDeadline", run.state().path("deadline").asText())
                        .put("pauseRequested", run.paused());
        result.set("payload", AgentJson.read(rs.getString("payload_json")));
        return result;
    }

    private static boolean approvalRunActive(Run run, Instant now) {
        if (run.cancelled()
                || run.paused()
                || !List.of("WAITING_APPROVAL", "WAITING_INPUT").contains(run.status()))
            return false;
        try {
            return Instant.parse(run.state().path("deadline").asText()).isAfter(now);
        } catch (DateTimeException exception) {
            return false;
        }
    }

    void requestApproval(Run run, JsonNode pending, String status) {
        tx.executeWithoutResult(
                transaction -> {
                    String id = UUID.randomUUID().toString();
                    jdbc.update(
                            """
INSERT INTO agent_approval(id,run_id,call_id,args_hash,payload_json,status,expires_at)
VALUES(?,?,?,?,?,'PENDING',?)
""",
                            id,
                            run.id(),
                            pending.path("id").asText(),
                            AgentJson.hash(pending),
                            pending.toString(),
                            Timestamp.from(Instant.parse(run.state().path("deadline").asText())));
                    checkpoint(
                            run,
                            status,
                            run.node(),
                            run.state(),
                            "APPROVAL_REQUESTED",
                            Map.of("approvalId", id, "call", pending));
                });
    }

    void decide(String id, int revision, String hash, boolean approved, String reason, long actor) {
        tx.executeWithoutResult(
                transaction -> {
                    JsonNode approval = approval(id);
                    String run = approval.path("run_id").asText();
                    jdbc.queryForObject(
                            "SELECT id FROM agent_run WHERE id=? FOR UPDATE", String.class, run);
                    Run current = get(run);
                    if (!approvalRunActive(current, Instant.now())
                            || !AgentJson.hash(current.state().path("toolIntent"))
                                    .equals(approval.path("args_hash").asText())
                            || !current.state()
                                    .path("toolIntent")
                                    .path("id")
                                    .asText()
                                    .equals(approval.path("call_id").asText())) throw conflict();
                    int updated =
                            jdbc.update(
                                    """
UPDATE agent_approval SET status=?,revision=revision+1,decided_by=?,reason=?
WHERE id=? AND status='PENDING' AND revision=? AND args_hash=? AND expires_at>NOW(3)
""",
                                    approved ? "APPROVED" : "REJECTED",
                                    actor,
                                    reason,
                                    id,
                                    revision,
                                    hash);
                    if (updated != 1) throw conflict();
                    jdbc.update(
                            """
UPDATE agent_run SET status='QUEUED',next_attempt=NOW(3),updated_at=NOW(3)
WHERE id=? AND status IN ('WAITING_APPROVAL','WAITING_INPUT')
""",
                            run);
                    event(
                            run,
                            approved ? "APPROVAL_GRANTED" : "APPROVAL_REJECTED",
                            "",
                            Map.of("approvalId", id, "actorId", actor, "reason", reason));
                    wake(run);
                });
    }

    List<JsonNode> approvals(String run) {
        return jdbc
                .queryForList(
                        "SELECT id FROM agent_approval WHERE run_id=? ORDER BY created_at",
                        String.class,
                        run)
                .stream()
                .map(this::approval)
                .toList();
    }

    void cancel(String id, long actor) {
        tx.executeWithoutResult(
                status -> {
                    jdbc.update(
                            """
UPDATE agent_run SET cancel_requested=TRUE,
status=CASE WHEN lease_until>NOW(3) THEN status ELSE 'QUEUED' END,next_attempt=NOW(3)
WHERE id=? AND status IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_INPUT')
""",
                            id);
                    event(id, "CANCEL_REQUESTED", "", Map.of("actorId", actor));
                    wake(id);
                });
    }

    void expireWaiting() {
        jdbc.update(
                """
UPDATE agent_run r SET status='QUEUED',next_attempt=NOW(3)
WHERE status IN ('WAITING_APPROVAL','WAITING_INPUT') AND EXISTS
(SELECT 1 FROM agent_approval a WHERE a.run_id=r.id AND a.status='PENDING' AND a.expires_at<NOW(3))
""");
    }

    void requestPause(String id, long actor) {
        tx.executeWithoutResult(
                transaction -> {
                    int changed =
                            jdbc.update(
                                    "UPDATE agent_run SET pause_requested=TRUE,status=CASE WHEN"
                                        + " status='QUEUED' THEN 'PAUSED' ELSE status"
                                        + " END,updated_at=NOW(3) WHERE id=? AND status IN"
                                        + " ('QUEUED','RUNNING','WAITING_APPROVAL','WAITING_INPUT')",
                                    id);
                    if (changed != 1) throw conflict();
                    event(
                            id,
                            "PAUSE_REQUESTED",
                            "",
                            Map.of("actorId", actor, "message", "在步骤边界暂停；已开始的远程动作不能撤回"));
                });
    }

    void resume(String id, long actor) {
        tx.executeWithoutResult(
                transaction -> {
                    jdbc.queryForObject(
                            "SELECT id FROM agent_runtime_guard WHERE id=1 FOR UPDATE",
                            Integer.class);
                    jdbc.queryForObject(
                            "SELECT id FROM agent_run WHERE id=? FOR UPDATE", String.class, id);
                    Run current = get(id);
                    if (current.cancelled()
                            || !List.of(
                                            "PAUSED",
                                            "NEEDS_ATTENTION",
                                            "WAITING_APPROVAL",
                                            "WAITING_INPUT")
                                    .contains(current.status())) throw conflict();
                    if (current.status().equals("NEEDS_ATTENTION")
                            && jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM agent_run WHERE status IN"
                                                + " ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_INPUT')",
                                            Long.class)
                                    >= 8) {
                        throw AgentJson.invalid("当前运行数已达演示环境上限");
                    }
                    if (!Instant.parse(current.state().path("deadline").asText())
                            .isAfter(Instant.now())) {
                        throw AgentJson.invalid("运行期限已到，恢复不会重置预算或期限");
                    }
                    var modelFailure = AgentModelFailure.fromState(current.state());
                    if (modelFailure != null) {
                        throw AgentJson.invalid(modelFailure.path("reason").asText());
                    }
                    jdbc.update(
                            "UPDATE agent_run SET pause_requested=FALSE,status=CASE WHEN status IN"
                                + " ('WAITING_APPROVAL','WAITING_INPUT') THEN status ELSE 'QUEUED'"
                                + " END,next_attempt=NOW(3),updated_at=NOW(3) WHERE id=?",
                            id);
                    event(
                            id,
                            "RESUME_REQUESTED",
                            "",
                            Map.of("actorId", actor, "message", "从同一持久意图继续；未知模型结果不会隐式重新付费调用"));
                    wake(id);
                });
    }

    void wake(String id) {
        jdbc.update(
                "INSERT INTO agent_outbox(id,run_id) VALUES(?,?)",
                UUID.randomUUID().toString(),
                id);
    }

    List<Map<String, Object>> outbox() {
        return jdbc.queryForList(
                "SELECT id,run_id FROM agent_outbox WHERE sent=FALSE ORDER BY created_at LIMIT 20");
    }

    void sent(String id) {
        jdbc.update("UPDATE agent_outbox SET sent=TRUE WHERE id=?", id);
    }

    void notifyRun(String id) {
        jdbc.update("UPDATE agent_run SET next_attempt=NOW(3) WHERE id=? AND status='QUEUED'", id);
    }

    void assertLease(Run run) {
        Long valid =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_run WHERE id=? AND fence=? AND"
                            + " lease_until>NOW(3) AND status='RUNNING' AND cancel_requested=FALSE"
                            + " AND pause_requested=FALSE",
                        Long.class,
                        run.id(),
                        run.fence());
        if (valid == null || valid != 1) throw conflict();
    }

    boolean approvalFresh(String id) {
        Long valid =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_approval WHERE id=?"
                                + " AND status='APPROVED' AND expires_at>NOW(3)",
                        Long.class,
                        id);
        return valid != null && valid == 1;
    }

    private static Run mapRun(ResultSet rs, int row) throws SQLException {
        return new Run(
                rs.getString("id"),
                rs.getLong("owner_id"),
                rs.getString("status"),
                rs.getString("node_id"),
                (ObjectNode) AgentJson.read(rs.getString("snapshot_json")),
                (ObjectNode) AgentJson.read(rs.getString("state_json")),
                rs.getLong("fence"),
                rs.getBoolean("cancel_requested"),
                rs.getBoolean("pause_requested"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static BusinessException missing() {
        return new BusinessException(ErrorCode.NOT_FOUND, "运行或定义不存在");
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "状态已变化，请刷新后重试");
    }

    /**
     * @author heyu
     */
    record Run(
            String id,
            long owner,
            String status,
            String node,
            ObjectNode snapshot,
            ObjectNode state,
            long fence,
            boolean cancelled,
            boolean paused,
            Instant createdAt) {}
}
