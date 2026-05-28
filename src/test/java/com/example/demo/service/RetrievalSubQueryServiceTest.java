package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalSubQueryServiceTest {

    @Test
    void shouldGenerateDeduplicatedSubQueriesAroundRewrittenQuery() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("""
                RagUnitService依赖注入
                RagUnitService使用了哪些Service
                它有哪些依赖
                RagUnitService依赖注入
                """);

        RetrievalSubQueryService service = new RetrievalSubQueryService(chatClient);

        List<String> subQueries = service.generateSubQueries("RagUnitService有哪些依赖", "它有哪些依赖");

        assertEquals(List.of(
                "RagUnitService有哪些依赖",
                "它有哪些依赖",
                "RagUnitService依赖注入",
                "RagUnitService使用了哪些Service"
        ), subQueries);
    }
}
