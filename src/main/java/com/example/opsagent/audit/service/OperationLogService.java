package com.example.opsagent.audit.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.audit.entity.OperationLog;
import com.example.opsagent.audit.vo.OperationLogVO;
import com.example.opsagent.common.api.PageResponse;

/**
 * 系统操作日志业务服务接口。
 *
 * @author heyu
 * @since 2026/7/15
 */
public interface OperationLogService extends IService<OperationLog> {

    PageResponse<OperationLogVO> pageLogs(Long pageNum, Long pageSize);

    OperationLogVO detail(Long id);
}
