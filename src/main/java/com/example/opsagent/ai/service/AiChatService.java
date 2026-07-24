package com.example.opsagent.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.ai.dto.AiChatLogQueryRequest;
import com.example.opsagent.ai.dto.AiChatRequest;
import com.example.opsagent.ai.entity.AiChatLog;
import com.example.opsagent.ai.vo.AiChatLogVO;
import com.example.opsagent.ai.vo.AiChatVO;
import com.example.opsagent.common.api.PageResponse;

public interface AiChatService extends IService<AiChatLog> {

    AiChatVO chat(AiChatRequest request);

    PageResponse<AiChatLogVO> pageLogs(AiChatLogQueryRequest request);

    AiChatLogVO logDetail(Long id);
}
