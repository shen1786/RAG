package com.example.demo.Config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SummaryWindowChatMemoryTest {

    @Mock
    private ChatMemoryRepository repository;
    @Mock
    private ChatClient summaryChatClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;
    private SummaryWindowChatMemory memory;

    private static final int MAX_MESSAGES = 4;
    private static final int SUMMARIZE_THRESHOLD = 6;
    private static final int ASSISTANT_TRUNCATE = 300;
    private static final int SUMMARY_MAX_LENGTH = 500;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        memory = new SummaryWindowChatMemory(
                repository, summaryChatClient, redisTemplate, objectMapper,
                MAX_MESSAGES, SUMMARIZE_THRESHOLD, ASSISTANT_TRUNCATE, SUMMARY_MAX_LENGTH);

        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void shouldReturnWindowMessagesWhenNoSummary() {
        List<Message> windowMessages = List.of(
                new UserMessage("你好"),
                new AssistantMessage("你好！有什么可以帮你的？")
        );
        when(repository.findByConversationId("s1")).thenReturn(windowMessages);
        when(valueOps.get("session:summary:s1")).thenReturn(null);

        List<Message> result = memory.get("s1");

        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof UserMessage);
        assertTrue(result.get(1) instanceof AssistantMessage);
    }

    @Test
    void shouldPrependSummaryAsSystemMessage() {
        List<Message> windowMessages = List.of(
                new UserMessage("第9条"),
                new AssistantMessage("第10条回复")
        );
        when(repository.findByConversationId("s1")).thenReturn(windowMessages);
        // 摘要现在存储为 JSON 格式 {"summary": "...", "count": N}
        String summaryJson = "{\"summary\":\"【用户目标】学习RAG\\n【已确认事实】使用Redis存储\",\"count\":8}";
        when(valueOps.get("session:summary:s1")).thenReturn(summaryJson);

        List<Message> result = memory.get("s1");

        assertEquals(3, result.size());
        assertTrue(result.get(0) instanceof SystemMessage);
        String systemText = result.get(0).getText();
        assertTrue(systemText.contains("会话摘要"));
        assertTrue(systemText.contains("学习RAG"));
        assertTrue(systemText.contains("Redis存储"));
        assertTrue(result.get(1) instanceof UserMessage);
        assertTrue(result.get(2) instanceof AssistantMessage);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAppendToFullHistoryOnAdd() {
        // 模拟空历史（第一条消息）
        when(listOps.range("session:full-history:s1", 0, -1)).thenReturn(new ArrayList<>());
        when(listOps.size("session:full-history:s1")).thenReturn(0L);

        List<Message> messages = List.of(new UserMessage("你好"));
        memory.add("s1", messages);

        // 验证调用了 rightPush 追加到完整历史
        verify(listOps).rightPush(eq("session:full-history:s1"), anyString());
        // 验证保存了窗口消息到 Repository
        verify(repository).saveAll(eq("s1"), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNotTriggerSummaryWhenBelowThreshold() {
        // 模拟 5 条历史（低于 SUMMARIZE_THRESHOLD=6）
        List<String> existingHistory = buildRawMessages(5);
        when(listOps.range("session:full-history:s1", 0, -1)).thenReturn(existingHistory);
        when(listOps.size("session:full-history:s1")).thenReturn(5L);

        List<Message> messages = List.of(new UserMessage("新消息"));
        memory.add("s1", messages);

        // 不应调用 summaryChatClient
        verifyNoInteractions(summaryChatClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSaveWindowedMessagesToRepository() {
        // 模拟 3 条历史，添加新消息后变成 4 条（等于 MAX_MESSAGES）
        // Mock range 返回 4 条（模拟 append 后的完整状态）
        List<String> fullAfterAdd = buildRawMessages(4);
        when(listOps.range("session:full-history:s1", 0, -1)).thenReturn(fullAfterAdd);
        when(listOps.size("session:full-history:s1")).thenReturn(4L);

        List<Message> messages = List.of(new UserMessage("第4条"));
        memory.add("s1", messages);

        // 应该保存全部 4 条到 repository（未超过 MAX_MESSAGES，不做窗口裁剪）
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(eq("s1"), captor.capture());
        List<Message> saved = captor.getValue();
        assertEquals(4, saved.size());
    }

    @Test
    void shouldClearAllStores() {
        memory.clear("s1");

        verify(repository).deleteByConversationId("s1");
        verify(redisTemplate).delete("session:full-history:s1");
        verify(redisTemplate).delete("session:summary:s1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnEmptyListWhenNoHistory() {
        when(listOps.range("session:full-history:s1", 0, -1)).thenReturn(null);

        List<Message> result = memory.getFullHistory("s1");

        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldParseFullHistoryDetail() {
        List<String> raw = List.of(
                "{\"type\":\"user\",\"content\":\"你好\",\"timestamp\":1000}",
                "{\"type\":\"assistant\",\"content\":\"你好！\",\"timestamp\":2000}"
        );
        when(listOps.range("session:full-history:s1", 0, -1)).thenReturn(raw);

        List<Map<String, Object>> detail = memory.getFullHistoryDetail("s1");

        assertEquals(2, detail.size());
        assertEquals("user", detail.get(0).get("type"));
        assertEquals("你好", detail.get(0).get("content"));
        assertEquals("assistant", detail.get(1).get("type"));
    }

    @Test
    void shouldReturnZeroCountWhenNoHistory() {
        when(listOps.size("session:full-history:s1")).thenReturn(null);

        long count = memory.getFullHistoryCount("s1");

        assertEquals(0, count);
    }

    private List<String> buildRawMessages(int count) {
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String type = (i % 2 == 0) ? "user" : "assistant";
            try {
                messages.add(objectMapper.writeValueAsString(Map.of(
                        "type", type,
                        "content", "消息" + (i + 1),
                        "timestamp", System.currentTimeMillis()
                )));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return messages;
    }
}
