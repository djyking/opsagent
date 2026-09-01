package com.opsagent.common.core;
public class BusinessException extends RuntimeException {private final ErrorCode errorCode;
 public BusinessException(ErrorCode errorCode,String message){super(message);this.errorCode=errorCode;} public ErrorCode getErrorCode(){return errorCode;}}
