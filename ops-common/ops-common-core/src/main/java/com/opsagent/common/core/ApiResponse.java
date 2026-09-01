package com.opsagent.common.core;
import org.slf4j.MDC;
public record ApiResponse<T>(int code, String message, T data, String traceId) {
 public static <T> ApiResponse<T> success(T data){return new ApiResponse<>(0,"success",data,MDC.get("traceId"));}
 public static ApiResponse<Void> success(){return success(null);}
 public static <T> ApiResponse<T> failure(int code,String message){return new ApiResponse<>(code,message,null,MDC.get("traceId"));}
}
