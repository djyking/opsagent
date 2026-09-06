package com.opsagent.platform;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.core.PageResult;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.security.SecurityUsers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 以固定步骤执行只读巡检与隔离故障恢复闭环，不允许任意工作流脚本或生产控制命令。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
@EnableScheduling
public class OperationsWorkflowService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObservabilityInspectionService observationInspections;

    private static final Logger LOG = LoggerFactory.getLogger(OperationsWorkflowService.class);
    private final OperationsWorkflowRepository repository;
    private final OperationsOverviewService overview;
    private final OperationsLabClient lab;
    private final Semaphore execution = new Semaphore(1);

    @Value("${ops.operations.inspection-enabled:false}")
    private boolean inspectionEnabled;

    @Value("${ops.operations.inspection-interval-ms:900000}")
    private long inspectionInterval;

    OperationsWorkflowService(
            OperationsWorkflowRepository repository,
            OperationsOverviewService overview,
            OperationsLabClient lab) {
        this.repository = repository;
        this.overview = overview;
        this.lab = lab;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverInterruptedRuns() {
        if (observationInspections != null) {
            repository.interruptExpiredInspections(observationInspections.recoverExpired());
        }
        repository.interruptUnfinished();
    }

    List<OperationsDtos.Workflow> workflows() {
        return List.of(
                new OperationsDtos.Workflow(
                        "HEALTH_CHECK",
                        "运行健康巡检",
                        "采集真实证据、执行阈值规则，再次检查服务采集状态。" + "只读执行，不自动改变业务服务。",
                        "READ_ONLY",
                        List.of("真实采集", "规则诊断", "二次验证"),
                        true,
                        "",
                        inspectionEnabled,
                        (int) Math.max(1, inspectionInterval / 60000),
                        repository.lastRunAt("HEALTH_CHECK")),
                new OperationsDtos.Workflow(
                        "ISOLATED_DRILL",
                        "隔离服务故障与恢复",
                        "独立实验容器中产生真实HTTP 503，" + "验证异常后恢复200；故障45秒自动到期。",
                        "ISOLATED",
                        List.of("基线检查", "注入隔离故障", "检测HTTP异常", "受控恢复", "再次验证"),
                        lab.configured(),
                        lab.configured() ? "" : "需要配置独立 operations-lab 容器及访问令牌。",
                        false,
                        0,
                        repository.lastRunAt("ISOLATED_DRILL")));
    }

    PageResult<OperationsDtos.Run> page(int page, int size) {
        return repository.page(page, size);
    }

    OperationsDtos.RunDetail detail(long id) {
        return repository.detail(id);
    }

    OperationsDtos.RunDetail start(OperationsDtos.StartRun request) {
        var actor = SecurityUsers.current();
        if (actor.roles().stream()
                .noneMatch(
                        role -> List.of("ADMIN", "OPS", "ROLE_ADMIN", "ROLE_OPS").contains(role))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或运维人员可以执行工作流");
        }
        OperationsDtos.Workflow workflow =
                workflows().stream()
                        .filter(item -> item.code().equals(request.workflowCode()))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION, "工作流类型不支持"));
        if (!workflow.available()) {
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, workflow.unavailableReason());
        }
        if (request.scenario() != null && !"SERVICE_UNAVAILABLE".equals(request.scenario())) {
            throw new BusinessException(ErrorCode.VALIDATION, "仅支持预定义隔离服务故障场景");
        }
        var reservation =
                observationInspections != null && "HEALTH_CHECK".equals(workflow.code())
                        ? observationInspections.reserveWorkflow(false, actor.userId())
                        : null;
        if (reservation != null && !reservation.acquired()) {
            throw new BusinessException(ErrorCode.CONFLICT, "已有健康巡检执行，本次跳过原因已记录");
        }
        if (!execution.tryAcquire()) {
            if (reservation != null)
                observationInspections.finish(reservation, "SKIPPED", "EXECUTOR_BUSY");
            throw new BusinessException(ErrorCode.CONFLICT, "已有工作流执行中，请完成后重试");
        }
        return execute(workflow, actor, reservation);
    }

    @Scheduled(
            fixedDelayString =
                    "#{T(java.lang.Math).max(60000,"
                        + " ${ops.operations.inspection-interval-ms:900000})}",
            initialDelayString = "${ops.operations.inspection-initial-delay-ms:120000}")
    void scheduledInspection() {
        InspectionExecutionRepository.Reservation reservation = null;
        try {
            if (observationInspections != null) {
                repository.interruptExpiredInspections(observationInspections.recoverExpired());
                reservation = observationInspections.reserveWorkflow(true, 0);
                if (reservation == null || !reservation.acquired()) return;
            } else if (!inspectionEnabled) return;
            OperationsDtos.Workflow workflow =
                    workflows().stream()
                            .filter(item -> "HEALTH_CHECK".equals(item.code()))
                            .findFirst()
                            .orElseThrow();
            if (execution.tryAcquire()) {
                execute(
                        workflow,
                        new OpsPrincipal(
                                0, "system-health", "scheduled-inspection", List.of("OPS")),
                        reservation);
            } else if (reservation != null) {
                observationInspections.finish(reservation, "SKIPPED", "EXECUTOR_BUSY");
            }
        } catch (RuntimeException exception) {
            if (reservation != null)
                observationInspections.finish(reservation, "FAILED", "WORKFLOW_FAILED");
            LOG.warn(
                    "Scheduled inspection unavailable: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private OperationsDtos.RunDetail execute(
            OperationsDtos.Workflow workflow,
            OpsPrincipal actor,
            InspectionExecutionRepository.Reservation reservation) {
        long runId = 0;
        try {
            runId = repository.start(workflow, actor);
            if (reservation != null) observationInspections.bindWorkflow(reservation, runId);
            repository.audit(runId, "START", "启动固定工作流；范围=" + workflow.mode(), actor.username());
            if ("HEALTH_CHECK".equals(workflow.code()))
                healthCheck(runId, actor.username(), reservation);
            else isolatedDrill(runId, actor.username());
            return repository.detail(runId);
        } catch (RuntimeException exception) {
            if (reservation != null)
                observationInspections.finish(reservation, "FAILED", "WORKFLOW_FAILED");
            if (runId > 0) {
                repository.finish(runId, "FAILED", "执行未完成，请查看失败步骤；系统没有执行生产服务控制命令。");
                repository.audit(
                        runId,
                        "FAILED",
                        "异常类型=" + exception.getClass().getSimpleName(),
                        actor.username());
                return repository.detail(runId);
            }
            throw exception;
        } finally {
            execution.release();
        }
    }

    private void healthCheck(
            long runId, String actor, InspectionExecutionRepository.Reservation reservation) {
        OperationsDtos.Overview snapshot =
                step(
                        runId,
                        1,
                        "真实采集",
                        actor,
                        () -> {
                            OperationsDtos.Overview data = overview.inspection();
                            int samples =
                                    data.metrics().stream()
                                            .mapToInt(OperationsDtos.Metric::sampleCount)
                                            .sum();
                            return new StepResult<>(
                                    data,
                                    "采集最近60分钟指标及服务采集状态；不查询需要登录认证的治理接口。",
                                    "采集时间="
                                            + data.capturedAt()
                                            + "；服务目标="
                                            + data.targets().size()
                                            + "；时序样本="
                                            + samples);
                        });
        step(
                runId,
                2,
                "规则诊断",
                actor,
                () ->
                        new StepResult<>(
                                true,
                                snapshot.summary(),
                                snapshot.risks().stream()
                                        .map(
                                                risk ->
                                                        risk.severity()
                                                                + "："
                                                                + risk.title()
                                                                + "；"
                                                                + risk.evidence())
                                        .reduce((left, right) -> left + "\n" + right)
                                        .orElse("已观测指标未越限")));
        List<OperationsDtos.Target> verified =
                step(
                        runId,
                        3,
                        "二次验证",
                        actor,
                        () -> {
                            List<OperationsDtos.Target> targets = overview.targets();
                            return new StepResult<>(
                                    targets,
                                    "重新读取Prometheus采集结果；此步骤不重启或修改服务。",
                                    targets.isEmpty()
                                            ? "无法读取采集目标，状态未知。"
                                            : targets.stream()
                                                    .map(
                                                            target ->
                                                                    target.service()
                                                                            + "="
                                                                            + target.health()
                                                                            + " @ "
                                                                            + target.observedAt())
                                                    .reduce((left, right) -> left + "\n" + right)
                                                    .orElse(""));
                        });
        boolean healthy =
                "HEALTHY".equals(snapshot.status())
                        && !verified.isEmpty()
                        && verified.stream().allMatch(target -> "up".equals(target.health()));
        if (reservation != null) {
            boolean observed =
                    step(
                            runId,
                            4,
                            "持久化服务巡检证据",
                            actor,
                            () -> {
                                boolean passed = observationInspections.capture(reservation);
                                return new StepResult<>(
                                        passed,
                                        "分别保存执行状态、检查结论与原始采样范围。",
                                        "inspectionRunId=" + reservation.runId());
                            });
            healthy = healthy && observed;
        }
        String summary = healthy ? "只读巡检完成；已观测范围未发现越限，验证目标均可抓取。" : "巡检已完成；存在运行风险或未知数据，需要人工结合证据确认。";
        repository.finish(runId, healthy ? "SUCCEEDED" : "ATTENTION", summary);
        repository.audit(runId, "COMPLETE", summary, actor);
    }

    private void isolatedDrill(long runId, String actor) {
        boolean mayBeFaulted = false;
        try {
            step(
                    runId,
                    1,
                    "基线检查",
                    actor,
                    () -> {
                        var probe = lab.health();
                        requireStatus(probe, 200);
                        return new StepResult<>(true, "独立实验容器基线正常。", probe.evidence());
                    });
            mayBeFaulted = true;
            step(
                    runId,
                    2,
                    "注入隔离故障",
                    actor,
                    () -> {
                        lab.fault();
                        return new StepResult<>(
                                true,
                                "只将实验容器健康状态切换为故障；45秒后自动恢复。",
                                "POST /fault 已得到独立实验服务确认；不操作应用、数据库或Docker。");
                    });
            step(
                    runId,
                    3,
                    "检测HTTP异常",
                    actor,
                    () -> {
                        var probe = lab.health();
                        requireStatus(probe, 503);
                        return new StepResult<>(true, "通过实际HTTP响应确认实验故障已发生。", probe.evidence());
                    });
            step(
                    runId,
                    4,
                    "受控恢复",
                    actor,
                    () -> {
                        lab.recover();
                        return new StepResult<>(
                                true, "调用预定义恢复动作，仅清除实验容器故障状态。", "POST /recover 已得到独立实验服务确认。");
                    });
            step(
                    runId,
                    5,
                    "再次验证",
                    actor,
                    () -> {
                        var probe = lab.health();
                        requireStatus(probe, 200);
                        return new StepResult<>(
                                true, "实验服务恢复健康，完成真实503→200验证闭环。", probe.evidence());
                    });
            mayBeFaulted = false;
            repository.finish(runId, "SUCCEEDED", "隔离演练完成：已真实观察HTTP 200→503→200；业务服务未受操作。");
            repository.audit(runId, "COMPLETE", "隔离故障注入、实际检测、固定恢复与再次验证均已完成。", actor);
        } finally {
            if (mayBeFaulted) {
                try {
                    lab.recover();
                    repository.audit(runId, "CLEANUP", "执行中断后已发送隔离容器恢复请求；需查看后续健康状态。", actor);
                } catch (RuntimeException ignored) {
                    repository.audit(
                            runId, "CLEANUP_UNCONFIRMED", "恢复请求无法确认；实验故障最多45秒自动到期。", actor);
                }
            }
        }
    }

    private <T> T step(
            long runId,
            int sequence,
            String title,
            String actor,
            Supplier<StepResult<T>> operation) {
        repository.startStep(runId, sequence, title);
        try {
            StepResult<T> result = operation.get();
            repository.finishStep(runId, sequence, "SUCCEEDED", result.detail(), result.evidence());
            repository.audit(runId, "STEP_COMPLETED", title + "：" + result.detail(), actor);
            return result.value();
        } catch (RuntimeException exception) {
            repository.finishStep(
                    runId,
                    sequence,
                    "FAILED",
                    "步骤未通过连接、范围或预期状态校验。",
                    exception instanceof OperationsLabClient.LabFailure failure
                            ? failure.evidence()
                            : "异常类型=" + exception.getClass().getSimpleName() + "；未获得成功证据。");
            throw exception;
        }
    }

    private void requireStatus(OperationsLabClient.Probe probe, int expected) {
        if (probe.httpStatus() != expected) {
            throw new OperationsLabClient.LabFailure(
                    "UNEXPECTED_HEALTH", "期望HTTP " + expected + "；" + probe.evidence());
        }
    }

    /**
     * @author heyu
     */
    private record StepResult<T>(T value, String detail, String evidence) {}
}
