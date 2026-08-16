package com.example.opsagent.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.ai.dto.AiChatLogQueryRequest;
import com.example.opsagent.ai.dto.AiChatRequest;
import com.example.opsagent.ai.entity.AiChatLog;
import com.example.opsagent.ai.vo.AiChatLogVO;
import com.example.opsagent.ai.vo.AiChatVO;
import com.example.opsagent.common.api.PageResponse;

/**
 * 定义工单文档问答、记录查询和引用返回能力。
 *
 * @author heyu
 * @since 2026/8/16
 */
public interface AiChatService extends IService<AiChatLog> {

    AiChatVO ask(Long ticketId, AiChatRequest request);

    PageResponse<AiChatLogVO> pageQuestions(Long ticketId, AiChatLogQueryRequest request);

    AiChatLogVO questionDetail(Long id);
}
