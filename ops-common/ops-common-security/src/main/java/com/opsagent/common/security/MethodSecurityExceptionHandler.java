package com.opsagent.common.security;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将方法级权限拒绝转换为明确的 403，避免通用异常处理误报系统错误。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MethodSecurityExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException exception) {
        return ResponseEntity.status(403)
                .body(ApiResponse.failure(ErrorCode.FORBIDDEN.code(), "无权访问该资源"));
    }
}
