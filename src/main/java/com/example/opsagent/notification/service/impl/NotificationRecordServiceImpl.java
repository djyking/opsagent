package com.example.opsagent.notification.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.notification.entity.NotificationRecord;
import com.example.opsagent.notification.dao.NotificationRecordDao;
import com.example.opsagent.notification.service.NotificationRecordService;
import com.example.opsagent.notification.vo.NotificationRecordVO;
import org.springframework.stereotype.Service;

@Service
public class NotificationRecordServiceImpl
        extends ServiceImpl<NotificationRecordDao, NotificationRecord>
        implements NotificationRecordService {

    @Override
    public PageResponse<NotificationRecordVO> pageRecords(Long pageNum, Long pageSize) {
        return PageResponse.empty(pageNum, pageSize);
    }

    @Override
    public NotificationRecordVO detail(Long id) {
        return new NotificationRecordVO();
    }

    @Override
    public NotificationRecordVO updateStatus(Long id, String status) {
        return new NotificationRecordVO();
    }
}
