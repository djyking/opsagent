package com.example.opsagent.common.exception;

import java.util.stream.Collectors;

import com.example.opsagent.common.api.ApiResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * @RestControllerAdvice springmvc统一的异常处理机制。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ApiResponse<Void> handleAuthException(AuthenticationException exception) {
        return ApiResponse.fail(ErrorCode.UNAUTHORIZED.getCode(), exception.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException exception) {
        return ApiResponse.fail(exception.getErrorCode().getCode(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return ApiResponse.fail(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(BindException.class)
    public ApiResponse<Void> handleBindException(BindException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return ApiResponse.fail(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        IllegalArgumentException.class
    })
    public ApiResponse<Void> handleBadRequestException(Exception exception) {
        return ApiResponse.fail(ErrorCode.BAD_REQUEST.getCode(), exception.getMessage());
    }

    /**
     * 按照最匹配原则进行匹配，不用担心会直接到这个
     * @param exception
     * @return
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception exception) {
        return ApiResponse.fail(ErrorCode.INTERNAL_ERROR);
    }
}
