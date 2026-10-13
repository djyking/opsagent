package com.opsagent.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.core.PageResult;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * 管理 CMDB Lite 和值班排班，并记录管理员的配置变更审计。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class ItsmPlatformService {
    private final ItsmPlatformRepository repository;
    private final PlatformAuditRepository audit;
    private final ObjectMapper json;

    ItsmPlatformService(
            ItsmPlatformRepository repository,
            PlatformAuditRepository audit,
            ObjectMapper json) {
        this.repository = repository;
        this.audit = audit;
        this.json = json;
    }

    List<Map<String, Object>> cis(String keyword, String type) {
        return repository.cis(keyword, type).stream().map(this::safeCi).toList();
    }

    Map<String, Object> ci(String ciCode) {
        return safeCi(required(repository.ci(ciCode), "配置项不存在"));
    }

    Map<String, Object> topology(String ciCode) {
        Map<String, Object> root = ci(ciCode);
        List<Map<String, Object>> edges = relations().stream().filter(edge ->
                ciCode.equals(edge.get("sourceCiCode")) || ciCode.equals(edge.get("targetCiCode"))).toList();
        Set<String> codes = new java.util.HashSet<>();
        codes.add(ciCode);
        edges.forEach(edge -> {
            codes.add(String.valueOf(edge.get("sourceCiCode")));
            codes.add(String.valueOf(edge.get("targetCiCode")));
        });
        return Map.of("root", root, "nodes", cis(null, null).stream()
                .filter(row -> codes.contains(row.get("ciCode"))).toList(), "edges", edges);
    }

    List<Map<String, Object>> relations() {
        return repository.relations();
    }

    List<Map<String, Object>> schedules() {
        return repository.schedules();
    }

    List<Map<String, Object>> shifts(Long scheduleId) {
        return repository.shifts(scheduleId);
    }

    @Transactional(readOnly = true)
    PageResult<OnCallShiftDtos.Shift> shiftPage(OnCallShiftDtos.PageQuery query) {
        LocalDateTime checkedAt = repository.shiftQueryTime();
        long total = repository.countShifts(query.scheduleId(), checkedAt);
        long lastPage = Math.max(1, (total + query.pageSize() - 1) / query.pageSize());
        long page = Math.min(query.pageNum(), lastPage);
        List<OnCallShiftDtos.Shift> records = total == 0 ? List.of() : repository.shiftPage(
                query.scheduleId(), checkedAt, (page - 1) * query.pageSize(), query.pageSize());
        return new PageResult<>(records, total, page, query.pageSize());
    }

    List<OnCallShiftDtos.Shift> shiftCalendar(OnCallShiftDtos.CalendarQuery query) {
        if (!query.endTime().isAfter(query.startTime())
                || query.endTime().isAfter(query.startTime().plusDays(31))) {
            throw new BusinessException(ErrorCode.VALIDATION, "日历结束时间须晚于开始时间，且区间不超过 31 天");
        }
        return repository.shiftCalendar(query);
    }

    CurrentOnCallResponse currentOnCall(String serviceCiCode) {
        return repository.currentOnCall(serviceCiCode);
    }

    @Transactional
    Map<String, Object> addCi(ItsmPlatformController.CiRequest request) {
        validateCi(request);
        long id;
        try {
            id = repository.addCi(
                    normalized(request.ciCode()),
                    request.ciName().trim(),
                    upper(request.ciType()),
                    upper(request.environment()),
                    nullable(request.ownerName()),
                    nullable(request.endpoint()),
                    upper(request.status()),
                    nullable(request.description()));
        } catch (DuplicateKeyException exception) {
            throw conflict("CI 编码已存在");
        }
        saveMetadata(request);
        audit("CMDB", Long.toString(id), "CMDB_CREATE", request);
        return safeCi(repository.ci(id));
    }

    @Transactional
    Map<String, Object> updateCi(long id, ItsmPlatformController.CiRequest request) {
        validateCi(request);
        Map<String, Object> current = required(repository.ci(id), "配置项不存在");
        if (!current.get("ciCode").equals(normalized(request.ciCode()))) {
            throw new BusinessException(ErrorCode.VALIDATION, "CI 编码是事件关联标识，创建后不能重命名");
        }
        try {
            if (repository.updateCi(
                            id,
                            normalized(request.ciCode()),
                            request.ciName().trim(),
                            upper(request.ciType()),
                            upper(request.environment()),
                            nullable(request.ownerName()),
                            nullable(request.endpoint()),
                            upper(request.status()),
                            nullable(request.description()))
                    == 0) {
                throw notFound("配置项不存在");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("CI 编码已存在");
        }
        saveMetadata(request);
        audit("CMDB", Long.toString(id), "CMDB_UPDATE", request);
        return safeCi(repository.ci(id));
    }

    @Transactional
    Map<String, Object> addRelation(ItsmPlatformController.RelationRequest request) {
        validateRelation(request);
        String source = normalized(request.sourceCiCode());
        String target = normalized(request.targetCiCode());
        if (source.equals(target)) {
            throw new BusinessException(ErrorCode.VALIDATION, "配置项不能依赖自身");
        }
        required(repository.ci(source), "源配置项不存在");
        required(repository.ci(target), "目标配置项不存在");
        long id;
        try {
            id = repository.addRelation(
                    source,
                    target,
                    upper(request.relationType()),
                    nullable(request.description()));
        } catch (DuplicateKeyException exception) {
            throw conflict("该依赖关系已存在");
        }
        audit("CMDB_RELATION", Long.toString(id), "CMDB_RELATION_CHANGE", request);
        return Map.of("id", id);
    }

    @Transactional
    void deleteRelation(long id) {
        if (repository.deleteRelation(id) == 0) {
            throw notFound("依赖关系不存在");
        }
        audit("CMDB_RELATION", Long.toString(id), "CMDB_RELATION_CHANGE", Map.of("deleted", true));
    }

    @Transactional
    void deleteCi(long id) {
        Map<String, Object> row = required(repository.ci(id), "配置项不存在");
        repository.archiveCi(String.valueOf(row.get("ciCode")));
        audit("CMDB", Long.toString(id), "CMDB_DELETE", Map.of("ciCode", row.get("ciCode")));
    }

    @Transactional
    Map<String, Object> updateRelation(long id, ItsmPlatformController.RelationRequest request) {
        validateRelation(request);
        required(repository.ci(request.sourceCiCode()), "源配置项不存在");
        required(repository.ci(request.targetCiCode()), "目标配置项不存在");
        try {
            if (repository.updateRelation(id, request) == 0) throw notFound("依赖关系不存在");
        } catch (DuplicateKeyException exception) {
            throw conflict("该依赖关系已存在");
        }
        audit("CMDB_RELATION", Long.toString(id), "CMDB_RELATION_CHANGE", request);
        return Map.of("id", id);
    }

    private void validateRelation(ItsmPlatformController.RelationRequest request) {
        if (request.sourceCiCode().equals(request.targetCiCode())) {
            throw new BusinessException(ErrorCode.VALIDATION, "配置项不能依赖自身");
        }
        if (!Set.of("CALLS", "ROUTES_TO", "DEPENDS_ON", "READS_FROM", "WRITES_TO", "PUBLISHES_TO",
                "CONSUMES_FROM", "AUTHENTICATES_WITH", "MONITORED_BY", "REGISTERS_TO")
                .contains(upper(request.relationType()))) {
            throw new BusinessException(ErrorCode.VALIDATION, "不支持的关系类型");
        }
    }

    private void validateCi(ItsmPlatformController.CiRequest request) {
        if (!request.ciCode().matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION, "CI 编码只支持字母、数字、点、横线、冒号和下划线");
        }
        String endpoint = request.endpoint();
        if (endpoint != null && (endpoint.contains("@") || endpoint.contains("?") || endpoint.contains("#"))) {
            throw new BusinessException(ErrorCode.VALIDATION, "访问地址不能包含凭据、查询参数或片段");
        }
    }

    private void saveMetadata(ItsmPlatformController.CiRequest request) {
        if (request.bindings() == null && request.systemName() == null && request.tags() == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bindings", request.bindings());
        metadata.put("systemName", request.systemName());
        metadata.put("tags", request.tags() == null ? List.of() : request.tags());
        try {
            repository.saveMetadata(request.ciCode(), json.writeValueAsString(metadata));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("观测绑定序列化失败", exception);
        }
    }

    private Map<String, Object> safeCi(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "ciCode", "ciName", "ciType", "environment", "ownerName",
                "endpoint", "status", "description", "updateTime")) {
            result.put(field, row.get(field));
        }
        Object metadata = row.get("metadataJson");
        if (metadata != null) {
            try {
                var value = json.readTree(String.valueOf(metadata));
                result.put("bindings", value.path("bindings"));
                result.put("systemName", value.path("systemName").asText(""));
                result.put("tags", value.path("tags"));
            } catch (JsonProcessingException ignored) {
                result.put("bindings", Map.of());
            }
        }
        result.put("endpoint", ObservabilitySanitizer.endpoint(result.get("endpoint")));
        return result;
    }

    @Transactional
    Map<String, Object> addSchedule(ItsmPlatformController.ScheduleRequest request) {
        validateZone(request.timezone());
        long id;
        try {
            id = repository.addSchedule(
                    normalized(request.scheduleCode()),
                    request.scheduleName().trim(),
                    nullable(request.serviceCiCode()),
                    request.timezone().trim(),
                    request.enabled());
        } catch (DuplicateKeyException exception) {
            throw conflict("排班编码已存在");
        }
        audit("ONCALL_SCHEDULE", Long.toString(id), "ONCALL_SHIFT_CREATE", request);
        return repository.schedule(id);
    }

    @Transactional
    Map<String, Object> updateSchedule(
            long id, ItsmPlatformController.ScheduleRequest request) {
        validateZone(request.timezone());
        try {
            if (repository.updateSchedule(
                            id,
                            normalized(request.scheduleCode()),
                            request.scheduleName().trim(),
                            nullable(request.serviceCiCode()),
                            request.timezone().trim(),
                            request.enabled())
                    == 0) {
                throw notFound("排班不存在");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("排班编码已存在");
        }
        audit("ONCALL_SCHEDULE", Long.toString(id), "ONCALL_SHIFT_UPDATE", request);
        return repository.schedule(id);
    }

    @Transactional
    Map<String, Object> addShift(ItsmPlatformController.ShiftRequest request) {
        validateShift(request);
        required(repository.schedule(request.scheduleId()), "排班不存在");
        long id;
        try {
            id = repository.addShift(
                    request.scheduleId(),
                    upper(request.roleType()),
                    request.userId(),
                    request.userName().trim(),
                    request.startTime(),
                    request.endTime());
        } catch (DuplicateKeyException exception) {
            throw conflict("同一排班、角色和开始时间的班次已存在");
        }
        audit("ONCALL_SHIFT", Long.toString(id), "ONCALL_SHIFT_CREATE", request);
        return repository.shift(id);
    }

    @Transactional
    Map<String, Object> updateShift(
            long id, ItsmPlatformController.ShiftRequest request) {
        validateShift(request);
        required(repository.schedule(request.scheduleId()), "排班不存在");
        try {
            if (repository.updateShift(
                            id,
                            request.scheduleId(),
                            upper(request.roleType()),
                            request.userId(),
                            request.userName().trim(),
                            request.startTime(),
                            request.endTime())
                    == 0) {
                throw notFound("班次不存在");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("同一排班、角色和开始时间的班次已存在");
        }
        audit("ONCALL_SHIFT", Long.toString(id), "ONCALL_SHIFT_UPDATE", request);
        return repository.shift(id);
    }

    @Transactional
    void deleteShift(long id) {
        if (repository.deleteShift(id) == 0) {
            throw notFound("班次不存在");
        }
        audit("ONCALL_SHIFT", Long.toString(id), "ONCALL_SHIFT_UPDATE", Map.of("deleted", true));
    }

    private void validateShift(ItsmPlatformController.ShiftRequest request) {
        String role = upper(request.roleType());
        if (!"PRIMARY".equals(role) && !"SECONDARY".equals(role)) {
            throw new BusinessException(ErrorCode.VALIDATION, "值班角色仅支持 PRIMARY 或 SECONDARY");
        }
        if (!request.endTime().isAfter(request.startTime())) {
            throw new BusinessException(ErrorCode.VALIDATION, "班次结束时间必须晚于开始时间");
        }
    }

    private void validateZone(String timezone) {
        try {
            ZoneId.of(timezone.trim());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "无效的时区");
        }
    }

    private void audit(String bizType, String bizId, String operation, Object detail) {
        try {
            audit.addPlatform(
                    bizType,
                    bizId,
                    operation,
                    SecurityUsers.current().userId(),
                    json.writeValueAsString(detail));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("ITSM 配置审计序列化失败", exception);
        }
    }

    private String normalized(String value) {
        return value.trim();
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String upper(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private <T> T required(T value, String message) {
        if (value == null) {
            throw notFound(message);
        }
        return value;
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
