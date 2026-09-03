package com.example.opsagent.notification.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.notification.entity.NotificationRecord;
import com.example.opsagent.notification.vo.NotificationRecordVO;

/**
 * 通知记录业务服务接口。
 *
 * @author heyu
 * @since 2026/7/15
 */
public interface NotificationRecordService extends IService<NotificationRecord> {

    PageResponse<NotificationRecordVO> pageRecords(Long pageNum, Long pageSize);

    NotificationRecordVO detail(Long id);

    NotificationRecordVO updateStatus(Long id, String status);
}
