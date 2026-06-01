package com.example.demo.service;

import com.example.demo.Config.HierarchyConfig;
import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HierarchySummaryServiceTest {

    @Mock
    private ChatClient summaryChatClient;

    @Mock
    private ChatClientRequestSpec requestSpec;

    @Mock
    private CallResponseSpec responseSpec;

    @Test
    void shouldFallbackQuicklyWhenSummaryCallTimesOut() {
        HierarchyConfig hierarchyConfig = new HierarchyConfig();
        hierarchyConfig.setSummaryTimeoutSeconds(1);
        hierarchyConfig.setSummaryFailureCooldownSeconds(30);

        HierarchySummaryService service = new HierarchySummaryService(
                summaryChatClient,
                new ObjectMapper(),
                hierarchyConfig
        );

        when(summaryChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenAnswer(invocation -> {
            Thread.sleep(1500);
            return requestSpec;
        });
        long start = System.currentTimeMillis();
        HierarchySummaryService.SummaryPayload payload = service.summarizeSection(
                "sample.docx",
                0,
                List.of(leaf("鼻鼽 allergic rhinitis 因肺气虚寒，卫表不固。"))
        );
        long duration = System.currentTimeMillis() - start;

        assertEquals("sample.docx - Section 1", payload.getTitle());
        assertTrue(payload.getSummary().contains("鼻鼽"));
        assertTrue(duration < 1400, "summary fallback should happen before the blocked call completes");
    }

    private static RagUnit leaf(String content) {
        RagUnit unit = new RagUnit();
        unit.setId("leaf-1");
        unit.setContent(content);
        unit.setSourceType(SourceType.TEXT);
        unit.setNodeType(RagNodeType.LEAF);
        return unit;
    }
}
