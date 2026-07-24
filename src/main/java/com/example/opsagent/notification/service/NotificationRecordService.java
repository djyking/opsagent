package com.example.opsagent.notification.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.notification.entity.NotificationRecord;
import com.example.opsagent.notification.vo.NotificationRecordVO;

public interface NotificationRecordService extends IService<NotificationRecord> {

    PageResponse<NotificationRecordVO> pageRecords(Long pageNum, Long pageSize);

    NotificationRecordVO detail(Long id);

    NotificationRecordVO updateStatus(Long id, String status);
}
