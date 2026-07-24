package com.example.opsagent.audit.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.audit.entity.OperationLog;
import com.example.opsagent.audit.dao.OperationLogDao;
import com.example.opsagent.audit.service.OperationLogService;
import com.example.opsagent.audit.vo.OperationLogVO;
import com.example.opsagent.common.api.PageResponse;
import org.springframework.stereotype.Service;

@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogDao, OperationLog>
        implements OperationLogService {

    @Override
    public PageResponse<OperationLogVO> pageLogs(Long pageNum, Long pageSize) {
        return PageResponse.empty(pageNum, pageSize);
    }

    @Override
    public OperationLogVO detail(Long id) {
        return new OperationLogVO();
    }
}
