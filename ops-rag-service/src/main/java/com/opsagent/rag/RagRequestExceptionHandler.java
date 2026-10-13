package com.opsagent.rag;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 流开始前的错误显式返回 JSON，避免 SSE 内容协商失败掩盖实际权限或证据错误。
 *
 * @author heyu
 * @since 2026/9/8
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {RagController.class, RagConversationController.class})
class RagRequestExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RagRequestExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> business(
            BusinessException exception, HttpServletRequest request) {
        return failure(exception.getErrorCode(), exception.getMessage(), request);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<ApiResponse<Void>> validation(Exception exception, HttpServletRequest request) {
        return failure(ErrorCode.VALIDATION, exception.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("RAG request preparation failed", exception);
        return failure(ErrorCode.SYSTEM_ERROR, "问答准备失败，请稍后重试", request);
    }

    private ResponseEntity<ApiResponse<Void>> failure(
            ErrorCode code, String message, HttpServletRequest request) {
        // 普通 JSON 接口沿用原有业务响应约定；流请求以正确 HTTP 状态供前端识别。
        int status = request.getRequestURI().endsWith("/stream") ? code.code() / 100 : 200;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(code.code(), message));
    }
}
