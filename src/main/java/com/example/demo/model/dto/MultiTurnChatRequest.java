package com.example.demo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多轮对话请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MultiTurnChatRequest {
    private String userId;
    private String sessionId;
    private Integer turnCount;
    private String message;
}
