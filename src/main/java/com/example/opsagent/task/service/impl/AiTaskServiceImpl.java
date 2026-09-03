package com.example.opsagent.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.common.exception.BusinessException;
import com.example.opsagent.common.exception.ErrorCode;
import com.example.opsagent.task.dao.AiTaskDao;
import com.example.opsagent.task.entity.AiTask;
import com.example.opsagent.task.service.AiTaskService;
import com.example.opsagent.task.vo.AiTaskVO;

import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 提供 AI 任务分页、详情和受控状态更新。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Service
public class AiTaskServiceImpl extends ServiceImpl<AiTaskDao, AiTask> implements AiTaskService {

    @Override
    public PageResponse<AiTaskVO> pageTasks(Long pageNum, Long pageSize) {
        validatePage(pageNum, pageSize);
        Page<AiTask> page =
                page(
                        new Page<>(pageNum, pageSize),
                        new LambdaQueryWrapper<AiTask>().orderByDesc(AiTask::getCreateTime));
        return PageResponse.from(page, this::toVO);
    }

    @Override
    public AiTaskVO detail(Long id) {
        return toVO(requireTask(id));
    }

    @Override
    public AiTaskVO updateStatus(Long id, String status) {
        AiTask task = requireTask(id);
        String targetStatus = normalizeStatus(status);
        if (!canTransition(task.getStatus(), targetStatus)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "不允许将 AI 任务状态从 " + task.getStatus() + " 修改为 " + targetStatus);
        }
        task.setStatus(targetStatus);
        if (!updateById(task)) {
            throw new BusinessException(ErrorCode.CONFLICT, "AI 任务状态更新失败");
        }
        return toVO(requireTask(id));
    }

    private AiTask requireTask(Long id) {
        AiTask task = getById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "AI 任务不存在");
        }
        return task;
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            throw new IllegalArgumentException("AI 任务状态不能为空");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("PENDING", "PROCESSING", "SUCCESS", "FAILED").contains(normalized)) {
            throw new IllegalArgumentException("AI 任务状态不合法");
        }
        return normalized;
    }

    private AiTaskVO toVO(AiTask task) {
        AiTaskVO result = new AiTaskVO();
        result.setId(task.getId());
        result.setBizType(task.getBizType());
        result.setBizId(task.getBizId());
        result.setTaskType(task.getTaskType());
        result.setStatus(task.getStatus());
        result.setRequestPayload(task.getRequestPayload());
        result.setResult(task.getResult());
        result.setCreateTime(task.getCreateTime());
        result.setUpdateTime(task.getUpdateTime());
        return result;
    }

    private boolean canTransition(String current, String target) {
        return switch (current) {
            case "PENDING" -> java.util.Set.of("PROCESSING", "FAILED").contains(target);
            case "PROCESSING" -> java.util.Set.of("SUCCESS", "FAILED").contains(target);
            default -> false;
        };
    }

    private void validatePage(Long pageNum, Long pageSize) {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageNum 必须大于 0，pageSize 必须在 1 到 100 之间");
        }
    }
}
