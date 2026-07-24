package com.example.opsagent.audit.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.audit.entity.OperationLog;
import com.example.opsagent.audit.vo.OperationLogVO;
import com.example.opsagent.common.api.PageResponse;

public interface OperationLogService extends IService<OperationLog> {

    PageResponse<OperationLogVO> pageLogs(Long pageNum, Long pageSize);

    OperationLogVO detail(Long id);
}
