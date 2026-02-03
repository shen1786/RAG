package com.example.demo.Config;

import com.example.demo.model.dto.UploadResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<UploadResponse> handleMaxSizeException(MaxUploadSizeExceededException exc) {
        return ResponseEntity.status(413) // Payload Too Large
                .body(UploadResponse.error("文件大小超过限制，请上传更小的文件"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<UploadResponse> handleException(Exception e) {
        // 打印堆栈信息以便调试
        e.printStackTrace();
        return ResponseEntity.internalServerError()
                .body(UploadResponse.error("服务器内部错误: " + e.getMessage()));
    }
}
