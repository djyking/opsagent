package com.opsagent.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        return repository.cis(keyword, type);
    }

    Map<String, Object> ci(String ciCode) {
        return required(repository.ci(ciCode), "配置项不存在");
    }

    Map<String, Object> topology(String ciCode) {
        return required(repository.topology(ciCode), "配置项不存在");
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

    Map<String, Object> currentOnCall(String serviceCiCode) {
        return repository.currentOnCall(serviceCiCode);
    }

    @Transactional
    Map<String, Object> addCi(ItsmPlatformController.CiRequest request) {
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
        audit("CMDB", Long.toString(id), "CMDB_CREATE", request);
        return repository.ci(id);
    }

    @Transactional
    Map<String, Object> updateCi(long id, ItsmPlatformController.CiRequest request) {
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
        audit("CMDB", Long.toString(id), "CMDB_UPDATE", request);
        return repository.ci(id);
    }

    @Transactional
    Map<String, Object> addRelation(ItsmPlatformController.RelationRequest request) {
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
