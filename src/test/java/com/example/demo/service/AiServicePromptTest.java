package com.example.demo.service;

import com.example.demo.model.dto.RetrievalMode;
import com.example.demo.model.dto.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiServicePromptTest {

    @Mock
    private UserProfileService userProfileService;

    @Test
    void shouldRequireAnswerToFollowKnowledgeReferencesWhenHit() {
        when(userProfileService.getProfile("u1")).thenReturn(null);
        AiService aiService = new AiService(
                null,
                null,
                null,
                null,
                userProfileService,
                null,
                null,
                null
        );
        RetrievalResult result = RetrievalResult.builder()
                .documents(List.of(Document.builder()
                        .id("leaf-1")
                        .text("1.编程实现“第二日”问题，为后续的测试做准备。")
                        .build()))
                .hit(true)
                .retrievalMode(RetrievalMode.FLAT_FALLBACK)
                .knowledgeText("1.编程实现“第二日”问题，为后续的测试做准备。")
                .candidateCount(1)
                .finalCount(1)
                .durationMs(10)
                .hierarchyHits(List.of())
                .build();

        String prompt = ReflectionTestUtils.invokeMethod(
                aiService,
                "buildMultiTurnSystemPrompt",
                "u1",
                result
        );

        assertTrue(prompt.contains("必须优先依据【知识库参考】回答"));
        assertTrue(prompt.contains("不得回答“知识库未出现”"));
        assertTrue(prompt.contains("引用中只看到"));
    }
}
