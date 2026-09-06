package com.opsagent.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 业务侧工具授权、乐观版本及幂等控制；Agent 不能绕过工单状态机。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class InternalTicketTools {
    private final TicketService service;
    private final TicketMapper tickets;
    private final AgentTicketEffectMapper effects;
    private final AlertEpisodeMapper episodes;
    private final ObjectMapper json;

    InternalTicketTools(
            TicketService service,
            TicketMapper tickets,
            AgentTicketEffectMapper effects,
            AlertEpisodeMapper episodes,
            ObjectMapper json) {
        this.service = service;
        this.tickets = tickets;
        this.effects = effects;
        this.episodes = episodes;
        this.json = json;
    }

    void target(InternalActorTokens.Context actor, Ticket ticket) {
        service.authorizeRead(ticket);
        if (!Objects.equals(actor.targetCode(), ticket.getAffectedCiCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "工具目标与运行绑定目标不一致");
        }
    }

    @Transactional
    JsonNode write(long ticketId, String operation, Call call, InternalActorTokens.Context actor) {
        if (!actor.runId().equals(call.runId())) throw denied("工具调用不属于当前运行");
        Ticket ticket = tickets.lock(ticketId);
        if (ticket == null) throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在");
        target(actor, ticket);
        service.authorizeWrite(ticket);
        if (call.input() == null
                || !call.input().isObject()
                || call.input().toString().length() > 12000) {
            throw new BusinessException(ErrorCode.VALIDATION, "工具 input 必须是有界对象");
        }
        String hash =
                digest(
                        ticketId
                                + "|"
                                + actor.userId()
                                + "|"
                                + operation
                                + "|"
                                + call.runId()
                                + "|"
                                + call.toolCallId()
                                + "|"
                                + call.expectedVersion()
                                + "|"
                                + canonical(call.input()));
        boolean inserted =
                effects.begin(
                                call.idempotencyKey(),
                                ticketId,
                                actor.userId(),
                                call.runId(),
                                call.toolCallId(),
                                operation,
                                hash)
                        == 1;
        AgentTicketEffectMapper.Effect effect = effects.lock(call.idempotencyKey());
        if (!hash.equals(effect.requestHash())) throw conflict("幂等键已用于不同的工具参数");
        if (!inserted) {
            if (effect.resultJson() == null) throw conflict("工具执行尚未完成");
            try {
                return json.readTree(effect.resultJson());
            } catch (Exception exception) {
                throw new IllegalStateException("无法读取幂等执行结果", exception);
            }
        }
        if (call.expectedVersion() != null
                && !Objects.equals(ticket.getVersion(), call.expectedVersion())) {
            throw conflict("工单版本已变化，请重新读取");
        }
        JsonNode input = call.input();
        Object result;
        switch (operation) {
            case "ai-analyses" -> {
                String summary = text(input, "summary", 1400, true);
                String recommendation = text(input, "recommendation", 500, false);
                String content =
                        summary + (recommendation.isBlank() ? "" : "\n建议：" + recommendation);
                result =
                        service.addWorkRecord(
                                ticketId,
                                new TicketDtos.AddWorkRecord(
                                        "DIAGNOSIS",
                                        content,
                                        text(input, "evidence", 1000, false)));
            }
            case "comments" ->
                    result =
                            service.comment(
                                    ticketId,
                                    new TicketDtos.AddComment(text(input, "content", 2000, true)));
            case "work-records" -> {
                String type = text(input, "recordType", 32, true);
                if (!Set.of("DIAGNOSIS", "ACTION", "VERIFICATION", "ROOT_CAUSE", "BUSINESS_REPLY")
                        .contains(type)) {
                    throw new BusinessException(ErrorCode.VALIDATION, "无效处置记录类型");
                }
                result =
                        service.addWorkRecord(
                                ticketId,
                                new TicketDtos.AddWorkRecord(
                                        type,
                                        text(input, "content", 2000, true),
                                        text(input, "evidence", 1000, false)));
            }
            case "transitions" -> {
                if (call.expectedVersion() == null) throw conflict("状态流转需要 expectedVersion");
                TicketStatus target;
                try {
                    target = TicketStatus.valueOf(text(input, "toStatus", 32, true));
                } catch (IllegalArgumentException exception) {
                    throw new BusinessException(ErrorCode.VALIDATION, "无效工单状态");
                }
                if ("ISOLATED".equals(ticket.getEnvironment())
                        && (target == TicketStatus.RESOLVED || target == TicketStatus.CLOSED)) {
                    var episode =
                            ticket.getEpisodeId() == null
                                    ? null
                                    : episodes.lock(ticket.getEpisodeId());
                    if (episode == null || !"resolved".equals(episode.currentStatus())) {
                        throw conflict("关联告警尚未恢复，不能报告处置完成");
                    }
                }
                result =
                        target == TicketStatus.ASSIGNED
                                ? service.claim(
                                        ticketId, new TicketDtos.Claim(call.expectedVersion()))
                                : service.transition(
                                        ticketId,
                                        new TicketDtos.Action(
                                                target,
                                                call.expectedVersion(),
                                                text(input, "comment", 512, false)));
            }
            default -> throw denied("工具不在允许范围");
        }
        JsonNode response = json.valueToTree(result);
        effects.complete(call.idempotencyKey(), response.toString());
        return response;
    }

    private String text(JsonNode node, String key, int max, boolean required) {
        JsonNode value = node.get(key);
        if (value != null && !value.isNull() && !value.isTextual())
            throw new BusinessException(ErrorCode.VALIDATION, key + " 必须是文本");
        String text = value == null || value.isNull() ? "" : value.asText().trim();
        if ((required && text.isBlank()) || text.length() > max)
            throw new BusinessException(ErrorCode.VALIDATION, key + " 为空或超过长度限制");
        return text;
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            TreeSet<String> keys = new TreeSet<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> sorted.set(key, canonical(node.get(key))));
            return sorted;
        }
        if (node.isArray()) {
            var array = json.createArrayNode();
            node.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return node;
    }

    private String digest(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private BusinessException denied(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN, message);
    }

    record Call(
            @NotBlank @Size(max = 128) String runId,
            @NotBlank @Size(max = 128) String toolCallId,
            @NotBlank @Size(max = 128) String idempotencyKey,
            @Min(0) Integer expectedVersion,
            @NotNull JsonNode input) {}
}
