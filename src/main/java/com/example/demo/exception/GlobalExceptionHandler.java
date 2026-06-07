package com.example.demo.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.example.demo.model.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ApiResponse<Object> handleMaxSizeException(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.error("文件上传大小超出限制", e);
        return ApiResponse.error(413, "文件大小超过限制，请上传更小的文件");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Object> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Bean Validation 校验失败: {}", message);
        return ApiResponse.validationError(message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Object> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("参数校验失败", e);
        return ApiResponse.validationError(e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ApiResponse<Object> handleIllegalStateException(IllegalStateException e) {
        log.error("状态异常错误", e);
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ApiResponse<Object> handleNotLoginException(NotLoginException e) {
        log.error("用户未登录", e);
        return ApiResponse.error(401, "未登录或登录已过期");
    }

    @ExceptionHandler(NotPermissionException.class)
    public ApiResponse<Object> handleNotPermissionException(NotPermissionException e) {
        log.error("用户权限不足，缺少权限: {}", e.getPermission(), e);
        return ApiResponse.error(403, "无权访问该接口");
    }

    @ExceptionHandler(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class)
    public ApiResponse<Object> handleCallNotPermittedException(io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
        log.warn("熔断器已打开，拒绝调用: {}", e.getMessage());
        return ApiResponse.error(503, "服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Object> handleException(Exception e) {
        log.error("系统未处理的异常: ", e);
        return ApiResponse.serverError("服务器内部错误，请稍后重试");
    }
}
