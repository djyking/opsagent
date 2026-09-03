package com.example.opsagent.task.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.task.entity.AiTask;
import com.example.opsagent.task.vo.AiTaskVO;

/**
 * AI 异步任务业务服务接口。
 *
 * @author heyu
 * @since 2026/7/15
 */
public interface AiTaskService extends IService<AiTask> {

    PageResponse<AiTaskVO> pageTasks(Long pageNum, Long pageSize);

    AiTaskVO detail(Long id);

    AiTaskVO updateStatus(Long id, String status);
}
