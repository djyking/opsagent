package com.example.opsagent.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.ai.dto.AiChatLogQueryRequest;
import com.example.opsagent.ai.dto.AiChatRequest;
import com.example.opsagent.ai.entity.AiChatLog;
import com.example.opsagent.ai.dao.AiChatLogDao;
import com.example.opsagent.ai.service.AiChatService;
import com.example.opsagent.ai.vo.AiChatLogVO;
import com.example.opsagent.ai.vo.AiChatVO;
import com.example.opsagent.common.api.PageResponse;
import org.springframework.stereotype.Service;

@Service
public class AiChatServiceImpl extends ServiceImpl<AiChatLogDao, AiChatLog> implements AiChatService {

    @Override
    public AiChatVO chat(AiChatRequest request) {
        return new AiChatVO();
    }

    @Override
    public PageResponse<AiChatLogVO> pageLogs(AiChatLogQueryRequest request) {
        return PageResponse.empty(request.getPageNum(), request.getPageSize());
    }

    @Override
    public AiChatLogVO logDetail(Long id) {
        return new AiChatLogVO();
    }
}
