package com.example.demo.Controller;

import com.alibaba.cloud.ai.memory.redis.RedisChatMemoryRepository;
import com.example.demo.Config.DateTimeTools;
import com.example.demo.Config.SessionManager;
import com.example.demo.model.dto.SessionDeleteRequest;
import com.example.demo.model.dto.MultiTurnChatRequest;
import com.example.demo.model.dto.RetrievalResult;
import com.example.demo.service.AiService;
import com.example.demo.service.QueryRewriteService;
import com.example.demo.service.RagRetrievalService;
import com.example.demo.service.RetrievalSubQueryService;
import com.example.demo.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiControllerTest {

    @Mock
    private ChatClient deepchatClient;

    @Mock
    private RagRetrievalService ragRetrievalService;

    @Mock
    private QueryRewriteService queryRewriteService;

    @Mock
    private RetrievalSubQueryService retrievalSubQueryService;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private DateTimeTools dateTimeTools;

    @Mock
    private RedisChatMemoryRepository chatMemoryRepository;

    @Mock
    private SessionManager sessionManager;

    @Test
    void shouldUseRewrittenQueryAsPrimaryAndOriginalQueryAsSupplementaryRecall() {
        MultiTurnChatRequest request = new MultiTurnChatRequest();
        request.setSessionId("s1");
        request.setMessage("它有哪些依赖");

        when(sessionManager.sessionExists("s1")).thenReturn(true);
        when(sessionManager.getUserIdBySession("s1")).thenReturn("u1");
        when(queryRewriteService.rewrite("s1", "它有哪些依赖")).thenReturn("RagUnitService有哪些依赖");
        when(retrievalSubQueryService.generateSubQueries("RagUnitService有哪些依赖", "它有哪些依赖"))
                .thenReturn(List.of("RagUnitService有哪些依赖", "RagUnitService依赖注入", "它有哪些依赖"));
        when(ragRetrievalService.retrieveWithMultiPathRecall(eq("RagUnitService有哪些依赖"), anyList(), eq("u1")))
                .thenReturn(RetrievalResult.empty(0));
        when(userProfileService.getProfile("u1")).thenReturn(null);
        stubChatClient();

        AiService aiService = new AiService(
                deepchatClient,
                ragRetrievalService,
                queryRewriteService,
                retrievalSubQueryService,
                userProfileService,
                dateTimeTools,
                chatMemoryRepository,
                sessionManager
        );
        AiController controller = new AiController(
                aiService
        );

        controller.multiTurnChat(request);

        ArgumentCaptor<List<String>> queriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(ragRetrievalService).retrieveWithMultiPathRecall(
                eq("RagUnitService有哪些依赖"),
                queriesCaptor.capture(),
                eq("u1")
        );
        assertEquals(List.of("RagUnitService有哪些依赖", "RagUnitService依赖注入", "它有哪些依赖"), queriesCaptor.getValue());
    }

    @Test
    void shouldExtractProfileBeforeDeletingSession() {
        SessionDeleteRequest request = new SessionDeleteRequest();
        request.setUserId("u1");
        request.setSessionId("s1");
        Message historyMessage = org.mockito.Mockito.mock(Message.class);
        List<Message> history = List.of(historyMessage);

        when(sessionManager.getUserIdBySession("s1")).thenReturn("u1");
        when(chatMemoryRepository.findByConversationId("s1")).thenReturn(history);

        AiService aiService = new AiService(
                deepchatClient,
                ragRetrievalService,
                queryRewriteService,
                retrievalSubQueryService,
                userProfileService,
                dateTimeTools,
                chatMemoryRepository,
                sessionManager
        );
        AiController controller = new AiController(aiService);

        controller.deleteSession(request);

        verify(userProfileService).extractProfileAsync("u1", history);
        verify(sessionManager).deleteSession("u1", "s1");
    }

    @SuppressWarnings("unchecked")
    private void stubChatClient() {
        ChatClient.ChatClientRequestSpec requestSpec = org.mockito.Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.AdvisorSpec advisorSpec = org.mockito.Mockito.mock(ChatClient.AdvisorSpec.class);
        ChatClient.StreamResponseSpec streamSpec = org.mockito.Mockito.mock(ChatClient.StreamResponseSpec.class);

        when(deepchatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.advisors(org.mockito.ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any())).thenAnswer(invocation -> {
            invocation.<Consumer<ChatClient.AdvisorSpec>>getArgument(0).accept(advisorSpec);
            return requestSpec;
        });
        when(advisorSpec.param(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(advisorSpec);
        when(requestSpec.tools(org.mockito.ArgumentMatchers.any())).thenReturn(requestSpec);
        when(requestSpec.system(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.user(org.mockito.ArgumentMatchers.anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.empty());
    }
}
