package com.example.opsagent.task.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.task.entity.AiTask;
import com.example.opsagent.task.dao.AiTaskDao;
import com.example.opsagent.task.service.AiTaskService;
import com.example.opsagent.task.vo.AiTaskVO;
import org.springframework.stereotype.Service;

@Service
public class AiTaskServiceImpl extends ServiceImpl<AiTaskDao, AiTask> implements AiTaskService {

    @Override
    public PageResponse<AiTaskVO> pageTasks(Long pageNum, Long pageSize) {
        return PageResponse.empty(pageNum, pageSize);
    }

    @Override
    public AiTaskVO detail(Long id) {
        return new AiTaskVO();
    }

    @Override
    public AiTaskVO updateStatus(Long id, String status) {
        return new AiTaskVO();
    }
}
