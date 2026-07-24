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

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai")
public class AiChatController {

    private final AiChatService aiChatService;

    @PostMapping("/chat")
    public ApiResponse<AiChatVO> chat(@Valid @RequestBody AiChatRequest request) {
        return ApiResponse.success(aiChatService.chat(request));
    }

    @GetMapping("/chat-logs")
    public ApiResponse<PageResponse<AiChatLogVO>> logs(@ModelAttribute AiChatLogQueryRequest request) {
        return ApiResponse.success(aiChatService.pageLogs(request));
    }

    @GetMapping("/chat-logs/{id}")
    public ApiResponse<AiChatLogVO> logDetail(@PathVariable Long id) {
        return ApiResponse.success(aiChatService.logDetail(id));
    }
}
