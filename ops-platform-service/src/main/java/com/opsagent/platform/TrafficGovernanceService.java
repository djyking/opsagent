package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Governed Sentinel changes: admin, validated schema, durable intent, Nacos CAS and client
 * confirmation.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class TrafficGovernanceService {
    private static final String SERVICE = "ops-rag-service";
    private static final String GROUP = "DEFAULT_GROUP";
    private final NacosConfigurationClient nacos;
    private final SentinelTrafficClient runtime;
    private final TrafficRuleValidator validator;
    private final TrafficChangeRepository changes;
    private final ObjectMapper json;

    TrafficGovernanceService(
            NacosConfigurationClient nacos,
            SentinelTrafficClient runtime,
            TrafficRuleValidator validator,
            TrafficChangeRepository changes,
            ObjectMapper json) {
        this.nacos = nacos;
        this.runtime = runtime;
        this.validator = validator;
        this.changes = changes;
        this.json = json;
    }

    TrafficGovernanceDtos.Summary summary(String ciCode) {
        SecurityUsers.current();
        if (ciCode != null && !ciCode.isBlank() && !SERVICE.equals(ciCode))
            return new TrafficGovernanceDtos.Summary(
                    "NOT_INTEGRATED",
                    ciCode,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "该服务尚未接入可验证的 Sentinel 资源与动态规则源。",
                    Instant.now());
        var snapshot = runtime.snapshot();
        var request =
                snapshot.resources().stream()
                        .filter(r -> r.resource().equals("ops-rag-request"))
                        .findFirst()
                        .orElse(null);
        var gate =
                snapshot.resources().stream()
                        .filter(r -> r.resource().equals("ops-rag-ask"))
                        .findFirst()
                        .orElse(null);
        int count = 0;
        for (String type : TrafficRuleValidator.TYPES) count += snapshot.rules().path(type).size();
        return new TrafficGovernanceDtos.Summary(
                snapshot.status(),
                SERVICE,
                snapshot.status().equals("AVAILABLE") ? snapshot.resources().size() : null,
                snapshot.status().equals("AVAILABLE") ? count : null,
                request == null ? null : request.passQps(),
                request == null
                        ? null
                        : sum(request.blockQps(), gate == null ? null : gate.blockQps()),
                request == null ? null : request.avgRt(),
                request == null ? null : request.activeThreads(),
                snapshot.status().equals("AVAILABLE")
                        ? "Sentinel 当前短滑动窗口，具体时长见各资源；RT/并发来自完整问答请求。空闲窗口会归零，暂无样本显示—；独立AI预算不计入。"
                        : "Sentinel 运行态暂不可读或未启用，指标未知。",
                snapshot.observedAt());
    }

    TrafficGovernanceDtos.Workspace workspace(String ciCode) {
        var summary = summary(ciCode);
        if (summary.status().equals("NOT_INTEGRATED"))
            return new TrafficGovernanceDtos.Workspace(summary, List.of(), List.of());
        List<TrafficGovernanceDtos.RuleSet> sets = new ArrayList<>();
        for (String type : List.of("FLOW", "DEGRADE", "SYSTEM")) sets.add(ruleSet(type));
        for (String type : List.of("PARAM_FLOW", "AUTHORITY"))
            sets.add(
                    new TrafficGovernanceDtos.RuleSet(
                            type,
                            label(type),
                            "NOT_INTEGRATED",
                            false,
                            false,
                            "",
                            "",
                            json.createArrayNode(),
                            json.createArrayNode(),
                            "NOT_INTEGRATED",
                            type.equals("PARAM_FLOW")
                                    ? "问答入口未传递稳定的热点参数，暂不开放无效规则。"
                                    : "尚无可信调用方来源标识，暂不开放授权规则。"));
        return new TrafficGovernanceDtos.Workspace(
                summary, runtime.snapshot().resources(), List.copyOf(sets));
    }

    TrafficGovernanceDtos.RuleSet ruleSet(String type) {
        var actor = SecurityUsers.current();
        requireType(type);
        var snapshot = runtime.snapshot();
        JsonNode applied;
        try {
            applied = validator.appliedProjection(type, snapshot.rules().path(type));
        } catch (BusinessException ignored) {
            applied = json.createArrayNode();
        }
        try {
            var raw = nacos.ruleContent(dataId(type), GROUP);
            JsonNode rules =
                    raw.value().isBlank() ? json.createArrayNode() : json.readTree(raw.value());
            rules = validator.validate(type, rules);
            String state =
                    !snapshot.status().equals("AVAILABLE")
                            ? "UNKNOWN"
                            : validator.equivalent(type, rules, snapshot.rules().path(type))
                                    ? "APPLIED"
                                    : "PENDING";
            if (state.equals("APPLIED") && raw.exists())
                changes.confirmPublished(type, raw.revision());
            return new TrafficGovernanceDtos.RuleSet(
                    type,
                    label(type),
                    raw.exists() ? "AVAILABLE" : "NOT_CONFIGURED",
                    true,
                    admin(actor) && snapshot.status().equals("AVAILABLE"),
                    dataId(type),
                    raw.revision(),
                    rules,
                    applied,
                    state,
                    raw.exists()
                            ? "Nacos 持久化规则与客户端已加载规则分别核对；只写固定RAG规则源。"
                            : "该类型尚未创建Nacos规则。管理员可通过首次受控发布创建。");
        } catch (Exception ignored) {
            return new TrafficGovernanceDtos.RuleSet(
                    type,
                    label(type),
                    "UNAVAILABLE",
                    true,
                    false,
                    dataId(type),
                    "",
                    json.createArrayNode(),
                    applied,
                    "UNKNOWN",
                    "Nacos读取失败或规则格式不受支持；已关闭发布，保留独立客户端运行态。");
        }
    }

    TrafficGovernanceDtos.Validated validate(String type, JsonNode rules) {
        requireAdmin();
        return new TrafficGovernanceDtos.Validated(
                true, validator.validate(type, rules), "规则已通过结构与资源范围校验；发布时仍需核对版本及真实应用结果。");
    }

    TrafficGovernanceDtos.History history(String type) {
        SecurityUsers.current();
        requireType(type);
        return new TrafficGovernanceDtos.History(changes.history(type));
    }

    TrafficGovernanceDtos.Result publish(String type, TrafficGovernanceDtos.Publish request) {
        return change(type, request, null);
    }

    TrafficGovernanceDtos.Result rollback(String type, TrafficGovernanceDtos.Rollback request) {
        requireAdmin();
        requireType(type);
        var prior = changes.get(request.versionId());
        if (prior == null || !prior.type().equals(type) || !prior.status().equals("APPLIED"))
            throw new BusinessException(ErrorCode.VALIDATION, "只能回退到该规则类型已确认生效的版本");
        return change(
                type,
                new TrafficGovernanceDtos.Publish(
                        prior.after(),
                        request.expectedRevision(),
                        request.requestId(),
                        request.comment()),
                prior.id());
    }

    private synchronized TrafficGovernanceDtos.Result change(
            String type, TrafficGovernanceDtos.Publish request, Long rollback) {
        var actor = requireAdmin();
        requireType(type);
        if (request.comment() == null
                || request.comment().isBlank()
                || request.comment().length() > 500
                || request.requestId() == null
                || !request.requestId().matches("[a-fA-F0-9-]{36}")
                || request.expectedRevision() == null
                || !request.expectedRevision().matches("[a-f0-9]{64}"))
            throw new BusinessException(ErrorCode.VALIDATION, "发布参数无效");
        JsonNode desired = validator.validate(type, request.rules());
        String hash =
                NacosConfigurationClient.sha256(
                        type
                                + "\n"
                                + desired
                                + "\n"
                                + request.expectedRevision()
                                + "\n"
                                + request.comment().trim()
                                + "\n"
                                + rollback);
        var existing = changes.find(request.requestId());
        if (existing != null) return replay(existing, hash, actor, type);
        if (!runtime.snapshot().status().equals("AVAILABLE"))
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "客户端不可读，暂不发布流量规则");
        NacosConfigurationClient.Content before;
        JsonNode prior;
        try {
            before = nacos.ruleContent(dataId(type), GROUP);
            prior =
                    validator.validate(
                            type,
                            before.value().isBlank()
                                    ? json.createArrayNode()
                                    : json.readTree(before.value()));
        } catch (Exception ignored) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "无法读取当前规则，未执行发布");
        }
        if (!before.revision().equals(request.expectedRevision()))
            throw new BusinessException(ErrorCode.CONFLICT, "规则已变化，请重新读取并核对差异");
        TrafficChangeRepository.Intent intent;
        try {
            intent = changes.reserve(type, request, hash, prior, desired, actor, rollback);
        } catch (DuplicateKeyException ignored) {
            return replay(changes.find(request.requestId()), hash, actor, type);
        }
        String state = "UNCONFIRMED";
        String message = "发布请求结果尚未确认；请刷新核对Nacos与客户端，不要重复创建发布。";
        try {
            if (!nacos.compareAndPublish(dataId(type), GROUP, before.value(), desired.toString())) {
                state = "REJECTED";
                message = "Nacos拒绝CAS发布，规则已被其他操作更新；未覆盖该变化。";
            } else {
                runtime.invalidate();
                var fresh = runtime.snapshot();
                var stored = nacos.ruleContent(dataId(type), GROUP);
                if (stored.value().equals(desired.toString())
                        && fresh.status().equals("AVAILABLE")
                        && validator.equivalent(type, desired, fresh.rules().path(type))) {
                    state = "APPLIED";
                    message = "Nacos已持久化，客户端已加载相同规则。";
                } else {
                    state = "PUBLISHED";
                    message = "Nacos已接收发布，客户端应用尚未确认；请刷新核对。";
                }
            }
        } catch (Exception ignored) {
            /* External mutation may have happened: never claim rejected or retry blindly. */
        }
        changes.finish(intent.change().id(), state, message, actor.userId());
        return new TrafficGovernanceDtos.Result(changes.get(intent.change().id()), ruleSet(type));
    }

    private TrafficGovernanceDtos.Result replay(
            TrafficChangeRepository.Intent intent, String hash, OpsPrincipal actor, String type) {
        if (intent == null || intent.actorId() != actor.userId() || !intent.hash().equals(hash))
            throw new BusinessException(ErrorCode.CONFLICT, "请求标识已用于其他发布");
        return new TrafficGovernanceDtos.Result(intent.change(), ruleSet(type));
    }

    private static void requireType(String type) {
        if (!TrafficRuleValidator.TYPES.contains(type))
            throw new BusinessException(ErrorCode.VALIDATION, "该规则类型尚未接入");
    }

    private static OpsPrincipal requireAdmin() {
        var actor = SecurityUsers.current();
        if (!admin(actor)) throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可发布或回退流量规则");
        return actor;
    }

    private static boolean admin(OpsPrincipal actor) {
        return actor.roles().contains("ADMIN") && !actor.roles().contains("DEMO");
    }

    private static Double sum(Double a, Double b) {
        return a == null && b == null ? null : (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    static String dataId(String type) {
        return "ops-rag-sentinel-" + type.toLowerCase(java.util.Locale.ROOT) + "-rules";
    }

    private static String label(String type) {
        return switch (type) {
            case "FLOW" -> "流控规则";
            case "DEGRADE" -> "熔断降级";
            case "SYSTEM" -> "系统保护";
            case "PARAM_FLOW" -> "热点参数";
            default -> "访问授权";
        };
    }
}
