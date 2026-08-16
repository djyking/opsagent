package com.example.opsagent.ai.controller;

import com.example.opsagent.ai.dto.AiChatLogQueryRequest;
import com.example.opsagent.ai.dto.AiChatRequest;
import com.example.opsagent.ai.service.AiChatService;
import com.example.opsagent.ai.vo.AiChatLogVO;
import com.example.opsagent.ai.vo.AiChatVO;
import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.common.api.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供工单文档问答、历史记录和引用详情接口。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class AiChatController {

    private final AiChatService aiChatService;

    @PostMapping("/tickets/{ticketId}/questions")
    public ApiResponse<AiChatVO> ask(@PathVariable Long ticketId, @Valid @RequestBody AiChatRequest request) {
        return ApiResponse.success(aiChatService.ask(ticketId, request));
    }

    @GetMapping("/tickets/{ticketId}/questions")
    public ApiResponse<PageResponse<AiChatLogVO>> questions(@PathVariable Long ticketId,
        @Valid @ModelAttribute AiChatLogQueryRequest request) {
        return ApiResponse.success(aiChatService.pageQuestions(ticketId, request));
    }

    @GetMapping("/questions/{id}")
    public ApiResponse<AiChatLogVO> detail(@PathVariable Long id) {
        return ApiResponse.success(aiChatService.questionDetail(id));
    }
}
