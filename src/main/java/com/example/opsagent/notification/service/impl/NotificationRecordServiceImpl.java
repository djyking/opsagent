package com.example.opsagent.notification.service.impl;

import java.util.Locale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.common.exception.BusinessException;
import com.example.opsagent.common.exception.ErrorCode;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.notification.entity.NotificationRecord;
import com.example.opsagent.notification.dao.NotificationRecordDao;
import com.example.opsagent.notification.service.NotificationRecordService;
import com.example.opsagent.notification.vo.NotificationRecordVO;
import org.springframework.stereotype.Service;

/**
 * 提供通知记录分页、详情及状态更新。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Service
public class NotificationRecordServiceImpl
        extends ServiceImpl<NotificationRecordDao, NotificationRecord>
        implements NotificationRecordService {

    @Override
    public PageResponse<NotificationRecordVO> pageRecords(Long pageNum, Long pageSize) {
        validatePage(pageNum, pageSize);
        Page<NotificationRecord> page = page(new Page<>(pageNum, pageSize),
            new LambdaQueryWrapper<NotificationRecord>().orderByDesc(NotificationRecord::getCreateTime));
        return PageResponse.from(page, this::toVO);
    }

    @Override
    public NotificationRecordVO detail(Long id) {
        return toVO(requireRecord(id));
    }

    @Override
    public NotificationRecordVO updateStatus(Long id, String status) {
        NotificationRecord record = requireRecord(id);
        String normalizedStatus = normalizeStatus(status);
        if (!"PENDING".equals(record.getStatus()) || "PENDING".equals(normalizedStatus)) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前通知状态不允许修改为 " + normalizedStatus);
        }
        record.setStatus(normalizedStatus);
        if (!updateById(record)) {
            throw new BusinessException(ErrorCode.CONFLICT, "通知状态更新失败");
        }
        return toVO(requireRecord(id));
    }

    private NotificationRecord requireRecord(Long id) {
        NotificationRecord record = getById(id);
        if (record == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "通知记录不存在");
        }
        return record;
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            throw new IllegalArgumentException("通知状态不能为空");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("PENDING", "SENT", "FAILED").contains(normalized)) {
            throw new IllegalArgumentException("通知状态只能是 PENDING、SENT 或 FAILED");
        }
        return normalized;
    }

    private NotificationRecordVO toVO(NotificationRecord record) {
        NotificationRecordVO result = new NotificationRecordVO();
        result.setId(record.getId());
        result.setTicketId(record.getTicketId());
        result.setReceiver(record.getReceiver());
        result.setTitle(record.getTitle());
        result.setContent(record.getContent());
        result.setStatus(record.getStatus());
        result.setCreateTime(record.getCreateTime());
        return result;
    }

    private void validatePage(Long pageNum, Long pageSize) {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageNum 必须大于 0，pageSize 必须在 1 到 100 之间");
        }
    }
}
