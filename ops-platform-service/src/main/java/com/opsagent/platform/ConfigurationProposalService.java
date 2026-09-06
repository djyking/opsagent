package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * An immutable whitelist intent handed to the existing Agent approval and executor.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class ConfigurationProposalService {
    private final ConfigCenterService center;
    private final ManagedConfigurationService managed;
    private final ManagedConfigurationRepository publications;
    private final ConfigurationProposalRepository repository;
    private static final List<String> FIELDS = List.of("catalogTitle", "notice", "discountPercent");

    ConfigurationProposalService(
            ConfigCenterService center,
            ManagedConfigurationService managed,
            ManagedConfigurationRepository publications,
            ConfigurationProposalRepository repository) {
        this.center = center;
        this.managed = managed;
        this.publications = publications;
        this.repository = repository;
    }

    ConfigurationProposalDtos.Proposal propose(
            String catalogId, ConfigurationProposalDtos.Create request) {
        OpsPrincipal actor = admin();
        if (request.requestId() == null
                || !request.requestId().matches("[a-f0-9-]{36}")
                || request.expectedRevision() == null
                || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.comment() == null
                || request.comment().isBlank()
                || request.comment().length() > 500) throw invalid("提案请求ID、基础版本或变更说明不合法。");
        String requestHash =
                ManagedConfigurationRepository.hash(catalogId + "|" + repository.write(request));
        var existing = repository.existing(request.requestId(), actor.userId(), requestHash);
        if (existing != null) return existing;
        var item = center.resolve(catalogId);
        if (!item.source().equals("NACOS")
                || !item.group().equals("OPSAGENT_DEMO")
                || !item.dataId().equals("ops-demo-order-business.json")
                || !item.identity().targetScope().equals(List.of(DemoTargetDtos.TARGET))
                || !item.capabilities().canEdit())
            throw new BusinessException(ErrorCode.FORBIDDEN, "此完整配置身份未纳入业务白名单。");
        var current = managed.detail("order-business");
        if (!current.canPublish()) throw conflict(current.blockedReason());
        if (!current.revision().equals(request.expectedRevision()))
            throw conflict("基础版本已变化，请重新读取后创建提案。");
        JsonNode desired;
        String action = request.rollbackVersionId() == null ? "PUBLISH" : "ROLLBACK";
        if (request.rollbackVersionId() != null) {
            if (request.patch() != null && !request.patch().isEmpty())
                throw invalid("回退版本与patch不可同时提交。");
            var version = publications.get(request.rollbackVersionId());
            if (!version.status().equals("APPLIED")) throw invalid("只能选择已确认应用的历史版本。");
            desired = managed.validate("order-business", version.content()).normalizedContent();
        } else desired = patch(current.content(), request.patch());
        var changes = new ArrayList<ConfigurationProposalDtos.Change>();
        for (String field : FIELDS)
            if (!current.content().path(field).equals(desired.path(field)))
                changes.add(
                        new ConfigurationProposalDtos.Change(
                                field, current.content().path(field), desired.path(field)));
        if (changes.isEmpty()) throw invalid("没有实际字段变化，不创建空变更。");
        JsonNode snapshot = current.application();
        if (!item.identity().namespaceId().equals(snapshot.path("namespaceId").asText())
                || !item.identity()
                        .sourceInstanceId()
                        .equals(snapshot.path("sourceInstanceId").asText()))
            throw conflict("目录源与实际目标读取的Nacos身份不一致，不允许跨源发布。");
        if (!snapshot.path("instanceId").asText().matches("[a-f0-9-]{36}")
                || !snapshot.path("targetCode").asText().equals(DemoTargetDtos.TARGET)
                || !fresh(snapshot.path("observedAt").asText()))
            throw conflict("缺少新鲜的真实目标实例身份，当前禁止创建发布提案。");
        String id = UUID.randomUUID().toString();
        Instant created = Instant.now();
        var proposal =
                new ConfigurationProposalDtos.Proposal(
                        id,
                        "",
                        "order-business",
                        DemoTargetDtos.TARGET,
                        catalogId,
                        item.identity(),
                        request.expectedRevision(),
                        current.content(),
                        desired,
                        List.copyOf(changes),
                        action,
                        request.rollbackVersionId(),
                        created,
                        created.plusSeconds(900),
                        "VALIDATED",
                        snapshot,
                        request.comment(),
                        actor.userId());
        String digest = ManagedConfigurationRepository.hash(repository.write(proposal));
        proposal =
                new ConfigurationProposalDtos.Proposal(
                        id,
                        digest,
                        proposal.configurationId(),
                        proposal.targetCode(),
                        catalogId,
                        proposal.identity(),
                        proposal.expectedRevision(),
                        proposal.before(),
                        proposal.desired(),
                        proposal.changes(),
                        proposal.action(),
                        proposal.rollbackVersionId(),
                        proposal.createdAt(),
                        proposal.expiresAt(),
                        proposal.status(),
                        proposal.targetSnapshot(),
                        proposal.comment(),
                        actor.userId());
        return repository.create(proposal, request.requestId(), requestHash);
    }

    ConfigurationProposalDtos.Proposal read(String id) {
        return repository.get(id, admin().userId());
    }

    ManagedConfigurationDtos.Result apply(
            String id, String digest, InternalActorTokens.Context actor) {
        OpsPrincipal current = admin();
        if (!DemoTargetDtos.TARGET.equals(actor.targetCode())
                || actor.userId() != current.userId()
                || actor.runId() == null
                || !actor.runId().matches("[a-f0-9-]{36}"))
            throw new BusinessException(ErrorCode.FORBIDDEN, "配置执行缺少精确目标与Agent运行身份。");
        var proposal = read(id);
        if (!proposal.immutableDigest().equals(digest)) throw conflict("提案摘要不匹配，旧审批不得执行新内容。");
        repository.bindRun(id, digest, actor.runId(), current.userId());
        var existing = publications.byRequestId(id);
        if (existing != null) {
            var actual = managed.detail("order-business");
            return new ManagedConfigurationDtos.Result(publications.get(existing.id()), actual);
        }
        if (!proposal.expiresAt().isAfter(Instant.now())) throw conflict("提案已过期，请重新创建并审批。");
        if (!center.resolve(proposal.catalogId()).identity().equals(proposal.identity()))
            throw conflict("完整来源身份已经变化，提案失效。");
        var snapshot = managed.applicationSnapshot();
        if (!fresh(snapshot.path("observedAt").asText())
                || !snapshot.path("instanceId")
                        .equals(proposal.targetSnapshot().path("instanceId")))
            throw conflict("审批时目标实例已变化，须重新创建提案。");
        if (proposal.action().equals("ROLLBACK"))
            return managed.rollback(
                    "order-business",
                    new ManagedConfigurationDtos.Rollback(
                            proposal.rollbackVersionId(),
                            proposal.expectedRevision(),
                            id,
                            proposal.comment()));
        return managed.publish(
                "order-business",
                new ManagedConfigurationDtos.Publish(
                        proposal.desired(), proposal.expectedRevision(), id, proposal.comment()));
    }

    private JsonNode patch(JsonNode current, List<ConfigurationProposalDtos.Patch> patches) {
        if (patches == null || patches.isEmpty() || patches.size() > 3)
            throw invalid("提交1至3个白名单字段变化。");
        ObjectNode desired = current.deepCopy();
        var seen = new HashSet<String>();
        for (var patch : patches) {
            if (patch == null) throw invalid("patch元素不能为空。");
            String field = patch.path() == null ? "" : patch.path().replaceFirst("^/", "");
            if (!FIELDS.contains(field) || !patch.path().equals("/" + field) || !seen.add(field))
                throw invalid("字段不在白名单或重复提交。");
            if (!"replace".equals(patch.op()))
                throw invalid("必需业务字段不允许删除；未提交字段保持不变，提示文本可明确置为空字符串。");
            if (patch.value() == null || patch.value().isNull())
                throw invalid("必需业务字段不允许null；空提示请提交空字符串。");
            if (patch.value().isTextual()
                    && patch.value().asText().contains(ConfigurationMasker.MASK))
                throw invalid("禁止将脱敏掩码写回源配置。");
            desired.set(field, patch.value());
        }
        return managed.validate("order-business", desired).normalizedContent();
    }

    private static boolean fresh(String value) {
        try {
            Instant at = Instant.parse(value);
            return at.isAfter(Instant.now().minusSeconds(30))
                    && at.isBefore(Instant.now().plusSeconds(5));
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static OpsPrincipal admin() {
        var actor = SecurityUsers.current();
        if (actor.userId() <= 0 || !actor.roles().contains("ADMIN"))
            throw new BusinessException(ErrorCode.FORBIDDEN, "配置变更提案与执行仅管理员可用。");
        return actor;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
