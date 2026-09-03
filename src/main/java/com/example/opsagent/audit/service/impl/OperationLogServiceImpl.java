package com.example.opsagent.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.audit.dao.OperationLogDao;
import com.example.opsagent.audit.entity.OperationLog;
import com.example.opsagent.audit.service.OperationLogService;
import com.example.opsagent.audit.vo.OperationLogVO;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.common.exception.BusinessException;
import com.example.opsagent.common.exception.ErrorCode;

import org.springframework.stereotype.Service;

/**
 * 提供操作审计日志分页和详情查询。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogDao, OperationLog>
        implements OperationLogService {

    @Override
    public PageResponse<OperationLogVO> pageLogs(Long pageNum, Long pageSize) {
        validatePage(pageNum, pageSize);
        Page<OperationLog> page =
                page(
                        new Page<>(pageNum, pageSize),
                        new LambdaQueryWrapper<OperationLog>()
                                .orderByDesc(OperationLog::getCreateTime));
        return PageResponse.from(page, this::toVO);
    }

    @Override
    public OperationLogVO detail(Long id) {
        OperationLog log = getById(id);
        if (log == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "操作日志不存在");
        }
        return toVO(log);
    }

    private OperationLogVO toVO(OperationLog log) {
        OperationLogVO result = new OperationLogVO();
        result.setId(log.getId());
        result.setBizType(log.getBizType());
        result.setBizId(log.getBizId());
        result.setOperationType(log.getOperationType());
        result.setOperator(log.getOperator());
        result.setContent(log.getContent());
        result.setCreateTime(log.getCreateTime());
        return result;
    }

    private void validatePage(Long pageNum, Long pageSize) {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageNum 必须大于 0，pageSize 必须在 1 到 100 之间");
        }
    }
}
