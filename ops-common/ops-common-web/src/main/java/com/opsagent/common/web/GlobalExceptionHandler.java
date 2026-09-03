package com.opsagent.common.web;

import com.opsagent.common.core.*;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/**
 * 将校验异常、业务异常和系统异常转换为统一 API 响应。
 *
 * @author heyu
 * @since 2026/7/25
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ApiResponse<Void> business(BusinessException e) {
        return ApiResponse.failure(e.getErrorCode().code(), e.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    ApiResponse<Void> validation(Exception e) {
        return ApiResponse.failure(ErrorCode.VALIDATION.code(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ApiResponse<Void> unexpected(Exception e) {
        log.error("Unhandled request error", e);
        return ApiResponse.failure(ErrorCode.SYSTEM_ERROR.code(), "系统内部错误");
    }
}
