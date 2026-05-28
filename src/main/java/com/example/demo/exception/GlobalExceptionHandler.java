package com.example.demo.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.example.demo.model.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ApiResponse<Object> handleMaxSizeException(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.error("File upload size exceeded", e);
        return ApiResponse.error(413, "文件大小超过限制，请上传更小的文件");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Object> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("Parameter validation error", e);
        return ApiResponse.validationError(e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ApiResponse<Object> handleIllegalStateException(IllegalStateException e) {
        log.error("State error", e);
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ApiResponse<Object> handleNotLoginException(NotLoginException e) {
        log.error("Not logged in", e);
        return ApiResponse.error(401, "未登录或登录已过期");
    }

    @ExceptionHandler(NotPermissionException.class)
    public ApiResponse<Object> handleNotPermissionException(NotPermissionException e) {
        log.error("Permission denied for permission: {}", e.getPermission(), e);
        return ApiResponse.error(403, "无权访问该接口");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Object> handleException(Exception e) {
        log.error("Unhandled exception: ", e);
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        return ApiResponse.serverError("服务器繁忙，处理超时，请稍后再试. 错误详情: " + msg);
    }
}
