package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 受控配置发布与回退；服务器执行校验、租约互斥、Nacos CAS和真实应用确认。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class ManagedConfigurationService {
    private static final List<ManagedConfigurationDtos.Definition> DEFINITIONS =
            List.of(
                    new ManagedConfigurationDtos.Definition(
                            "order-business",
                            "订单展示与报价",
                            "OPSAGENT_DEMO",
                            "ops-demo-order-business.json",
                            true,
                            "独立订单服务真实读取并应用的标题、提示及演示报价折扣；基准演示价格为100。"),
                    new ManagedConfigurationDtos.Definition(
                            "order-runtime",
                            "隔离演练运行配置",
                            "OPSAGENT_DEMO",
                            "ops-demo-order-runtime.json",
                            false,
                            "Nacos驱动Redis连接和Sentinel预设；由演练、审批与TTL管理，此处仅核对当前配置。"));
    private final ManagedConfigurationRepository repository;
    private final DemoTargetRepository incidents;
    private final DemoTargetClient target;
    private final DemoTargetService demo;
    private final ObjectMapper json;

    ManagedConfigurationService(
            ManagedConfigurationRepository repository,
            DemoTargetRepository incidents,
            DemoTargetClient target,
            DemoTargetService demo,
            ObjectMapper json) {
        this.repository = repository;
        this.incidents = incidents;
        this.target = target;
        this.demo = demo;
        this.json = json;
    }

    List<ManagedConfigurationDtos.Definition> definitions() {
        SecurityUsers.current();
        return DEFINITIONS;
    }

    ManagedConfigurationDtos.Detail detail(String id) {
        OpsPrincipal actor = SecurityUsers.current();
        var definition = definition(id);
        JsonNode view = json.createObjectNode();
        JsonNode business = json.createObjectNode();
        String blocked = "";
        try {
            view = target.managedConfiguration(id);
            if (id.equals("order-business")
                    && "APPLIED".equals(view.path("applicationStatus").asText())
                    && view.path("revision")
                            .asText()
                            .equals(view.path("appliedRevision").asText())) {
                repository.reconcile(
                        view.path("publicationId").asText(),
                        view.path("revision").asText(),
                        safeContent(id, view.path("content")));
            }
            JsonNode runtime = target.snapshot();
            business = safeBusiness(target.preview());
            Instant available = incidents.availableAfter();
            if (!"BASELINE".equals(runtime.path("status").asText()) || incidents.active() != null) {
                blocked = "目标正在演练，恢复后才能发布配置";
            } else if (available != null && available.isAfter(Instant.now()))
                blocked = "目标正在冷却，请稍后发布";
            else if (incidents.configurationBusy()) blocked = "已有配置发布正在确认";
            else if (!"APPLIED".equals(runtime.path("configurationStatus").asText())
                    || !"APPLIED".equals(view.path("applicationStatus").asText())
                    || !view.path("revision")
                            .asText()
                            .equals(view.path("appliedRevision").asText())) {
                blocked = "Nacos配置尚未确认应用，请刷新核对";
            } else if (business.path("httpStatus").asInt() != 200) blocked = "业务健康基线尚未恢复";
        } catch (RuntimeException exception) {
            blocked = "Nacos或独立目标暂不可用，当前禁止发布";
        }
        if (!definition.editable()) blocked = "运行配置由演练、审批和TTL管理，只读查看";
        else if (!administrator(actor)) blocked = "当前账号只读，发布和回退需要管理员";
        return new ManagedConfigurationDtos.Detail(
                id,
                definition.name(),
                definition.group(),
                definition.dataId(),
                definition.editable(),
                definition.description(),
                safeContent(id, view.path("content")),
                view.path("revision").asText(),
                view.path("appliedRevision").asText(),
                view.path("applicationStatus").asText("UNAVAILABLE"),
                view.path("nacosStatus").asText("UNAVAILABLE"),
                definition.editable() && administrator(actor) && blocked.isBlank(),
                blocked,
                Instant.now(),
                business);
    }

    ManagedConfigurationDtos.Validated validate(String id, JsonNode content) {
        requireAdmin();
        requireWritable(id);
        JsonNode normalized = validateContent(content);
        return new ManagedConfigurationDtos.Validated(
                true, normalized, "三个字段校验通过；发布后由真实Nacos通知独立订单服务应用，实时业务预览用于核对结果。");
    }

    ManagedConfigurationDtos.Result publish(String id, ManagedConfigurationDtos.Publish request) {
        requireWritable(id);
        OpsPrincipal actor = requireAdmin();
        JsonNode content = validateContent(request.content());
        return change(
                new ManagedConfigurationDtos.Publish(
                        content,
                        request.expectedRevision(),
                        request.requestId(),
                        request.comment()),
                "PUBLISH",
                null,
                actor);
    }

    ManagedConfigurationDtos.Result rollback(String id, ManagedConfigurationDtos.Rollback request) {
        requireWritable(id);
        OpsPrincipal actor = requireAdmin();
        var version = repository.get(request.versionId());
        if (!"APPLIED".equals(version.status())) throw invalid("只能回退到已确认生效的历史版本");
        JsonNode content = validateContent(version.content());
        return change(
                new ManagedConfigurationDtos.Publish(
                        content,
                        request.expectedRevision(),
                        request.requestId(),
                        request.comment()),
                "ROLLBACK",
                request.versionId(),
                actor);
    }

    ManagedConfigurationDtos.HistoryPage history(String id, int page, int size) {
        SecurityUsers.current();
        definition(id);
        if (page < 1 || page > 10000 || size < 1 || size > 50) throw invalid("分页参数超出范围");
        return repository.history(id, page, size);
    }

    private ManagedConfigurationDtos.Result change(
            ManagedConfigurationDtos.Publish request,
            String action,
            Long rollbackId,
            OpsPrincipal actor) {
        if (request.requestId() == null
                || !request.requestId().matches("[a-f0-9-]{36}")
                || request.expectedRevision() == null
                || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.comment() == null
                || request.comment().isBlank()
                || request.comment().length() > 500) {
            throw invalid("发布参数、版本或说明不合法");
        }
        String hash =
                ManagedConfigurationRepository.hash(
                        action
                                + "|"
                                + rollbackId
                                + "|"
                                + request.expectedRevision()
                                + "|"
                                + request.content()
                                + "|"
                                + request.comment());
        synchronized (demo) {
            var existing = repository.existing(request.requestId(), hash, actor.userId());
            if (existing != null)
                return new ManagedConfigurationDtos.Result(existing, detail("order-business"));
            var before = detail("order-business");
            if (!before.canPublish())
                throw new BusinessException(ErrorCode.CONFLICT, before.blockedReason());
            if (!before.revision().equals(request.expectedRevision())) {
                throw new BusinessException(ErrorCode.CONFLICT, "配置版本已变化，请刷新后重新核对");
            }
            var entry =
                    repository.reserve(request, before.content(), action, rollbackId, hash, actor);
            if (!entry.status().equals("REQUESTED")) {
                return new ManagedConfigurationDtos.Result(entry, detail("order-business"));
            }
            String status = "UNCONFIRMED";
            String revision = "";
            String message = "发布请求已登记，尚未确认远端结果";
            try {
                JsonNode result =
                        target.publishBusinessConfiguration(
                                request.content(), request.expectedRevision(), request.requestId());
                revision = result.path("revision").asText();
                if (revision.matches("[a-f0-9]{64}")
                        && revision.equals(result.path("appliedRevision").asText())
                        && result.path("content").equals(request.content())
                        && result.path("publicationId").asText().equals(request.requestId())
                        && "APPLIED".equals(result.path("applicationStatus").asText())) {
                    status = "APPLIED";
                    message = "Nacos版本与目标已应用版本一致，配置已生效";
                } else message = "Nacos发布返回，但目标版本或内容尚未确认一致，请刷新核对";
            } catch (RuntimeException exception) {
                message = "发布结果尚未确认，请刷新查看实际版本；未把请求成功当成业务生效";
            }
            repository.complete(
                    entry.id(), request.requestId(), status, revision, message, actor.userId());
            return new ManagedConfigurationDtos.Result(
                    repository.get(entry.id()), detail("order-business"));
        }
    }

    private JsonNode validateContent(JsonNode value) {
        if (value == null
                || !value.isObject()
                || value.size() != 3
                || value.toString().length() > 4096
                || !value.path("catalogTitle").isTextual()
                || !value.path("notice").isTextual()
                || !value.path("discountPercent").isIntegralNumber()
                || !value.path("discountPercent").canConvertToInt()) {
            throw invalid("配置必须且只能包含catalogTitle、notice和整数discountPercent");
        }
        String title = value.path("catalogTitle").asText();
        String notice = value.path("notice").asText();
        int discount = value.path("discountPercent").asInt();
        if (title.isBlank()
                || title.length() > 60
                || notice.length() > 160
                || discount < 0
                || discount > 30
                || title.chars().anyMatch(Character::isISOControl)
                || notice.chars().anyMatch(Character::isISOControl)) {
            throw invalid("标题须为1至60字符，提示最多160字符，折扣须为0至30的整数，文本不能包含控制字符");
        }
        return json.createObjectNode()
                .put("catalogTitle", title)
                .put("notice", notice)
                .put("discountPercent", discount);
    }

    private JsonNode safeContent(String id, JsonNode value) {
        if (!value.isObject()) return json.createObjectNode();
        if (id.equals("order-business")) {
            try {
                return validateContent(value);
            } catch (BusinessException exception) {
                return json.createObjectNode();
            }
        }
        ObjectNode safe = json.createObjectNode();
        for (String field :
                List.of(
                        "scenarioCode",
                        "revision",
                        "expiresAtEpoch",
                        "redisPort",
                        "qps",
                        "recoverySource")) {
            JsonNode item = value.path(field);
            if (item.isValueNode() && item.toString().length() <= 150) safe.set(field, item);
        }
        return safe;
    }

    private JsonNode safeBusiness(JsonNode value) {
        ObjectNode safe = json.createObjectNode();
        for (String field :
                List.of(
                        "httpStatus",
                        "reasonCode",
                        "observedAt",
                        "catalogTitle",
                        "notice",
                        "discountPercent",
                        "basePrice",
                        "quotedPrice",
                        "catalog",
                        "businessConfigurationRevision")) {
            JsonNode item = value.path(field);
            if (item.isValueNode() && item.toString().length() <= 500) safe.set(field, item);
        }
        return safe;
    }

    private ManagedConfigurationDtos.Definition definition(String id) {
        return DEFINITIONS.stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "此配置项未纳入受控管理"));
    }

    private void requireWritable(String id) {
        if (!definition(id).editable())
            throw new BusinessException(ErrorCode.FORBIDDEN, "演练运行配置只读，请使用演练与审批流程");
    }

    private OpsPrincipal requireAdmin() {
        OpsPrincipal actor = SecurityUsers.current();
        if (!administrator(actor))
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有ADMIN可以发布或回退受控配置");
        return actor;
    }

    private static boolean administrator(OpsPrincipal actor) {
        return actor.roles().contains("ADMIN");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
