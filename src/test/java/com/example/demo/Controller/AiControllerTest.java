package com.example.demo.Controller;

import com.example.demo.Config.DateTimeTools;
import com.example.demo.model.dto.SessionDeleteRequest;
import com.example.demo.model.dto.HierarchyHit;
import com.example.demo.model.dto.MultiTurnChatRequest;
import com.example.demo.model.dto.RetrievalResult;
import com.example.demo.service.AiService;
import com.example.demo.service.AuthContextService;
import com.example.demo.service.AsrService;
import com.example.demo.service.ChatSessionService;
import com.example.demo.service.QueryRewriteService;
import com.example.demo.service.RagRetrievalService;
import com.example.demo.service.RetrievalSubQueryService;
import com.example.demo.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private ChatSessionService chatSessionService;

    @Mock
    private AuthContextService authContextService;

    @Mock
    private AsrService asrService;

    @Test
    void shouldUseRewrittenQueryAsPrimaryAndOriginalQueryAsSupplementaryRecall() {
        MultiTurnChatRequest request = new MultiTurnChatRequest();
        request.setSessionId("s1");
        request.setMessage("它有哪些依赖");

        when(chatSessionService.requireActiveSessionUser("s1")).thenReturn("u1");
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
                chatSessionService
        );
        AiController controller = new AiController(
                aiService,
                authContextService,
                asrService
        );

        when(authContextService.resolveUserId("u1")).thenReturn("u1");
        request.setUserId("u1");
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

        com.example.demo.model.dto.SessionDeleteResponse deleteResponse =
                new com.example.demo.model.dto.SessionDeleteResponse("s1", System.currentTimeMillis(), "ok");
        when(chatSessionService.deleteSession(request))
                .thenReturn(com.example.demo.model.dto.ApiResponse.success(deleteResponse));

        AiService aiService = new AiService(
                deepchatClient,
                ragRetrievalService,
                queryRewriteService,
                retrievalSubQueryService,
                userProfileService,
                dateTimeTools,
                chatSessionService
        );
        when(authContextService.resolveUserId("u1")).thenReturn("u1");
        AiController controller = new AiController(aiService, authContextService, asrService);

        controller.deleteSession(request);

        verify(chatSessionService).deleteSession(request);
    }

    @Test
    void shouldEmitCitationHierarchyMetadataBeforeTokens() {
        MultiTurnChatRequest request = new MultiTurnChatRequest();
        request.setSessionId("s1");
        request.setUserId("u1");
        request.setMessage("总结文档");

        when(chatSessionService.requireActiveSessionUser("s1")).thenReturn("u1");
        when(queryRewriteService.rewrite("s1", "总结文档")).thenReturn("总结文档");
        when(retrievalSubQueryService.generateSubQueries("总结文档", "总结文档")).thenReturn(List.of("总结文档"));
        when(ragRetrievalService.retrieveWithMultiPathRecall(eq("总结文档"), anyList(), eq("u1")))
                .thenReturn(RetrievalResult.builder()
                        .hit(true)
                        .hierarchyHits(List.of(HierarchyHit.builder()
                                .filename("report.pdf")
                                .minioUrl("http://example.com/report.pdf")
                                .docTitle("年度报告")
                                .sectionTitle("财务概览")
                                .leafChunkIndex(2)
                                .leafScore(0.91)
                                .content("这是引用内容")
                                .build()))
                        .build());
        when(userProfileService.getProfile("u1")).thenReturn(null);
        stubChatClientWithContent(Flux.just("首段"));

        AiService aiService = new AiService(
                deepchatClient,
                ragRetrievalService,
                queryRewriteService,
                retrievalSubQueryService,
                userProfileService,
                dateTimeTools,
                chatSessionService
        );

        List<ServerSentEvent<String>> events = aiService.multiTurnChat(request).collectList().block();

        assertEquals("citations", events.get(0).event());
        assertTrue(events.get(0).data().contains("\"docTitle\":\"年度报告\""));
        assertTrue(events.get(0).data().contains("\"sectionTitle\":\"财务概览\""));
        assertTrue(events.get(0).data().contains("\"chunkIndex\":3"));
        assertEquals("message", events.get(1).event());
        assertEquals("首段", events.get(1).data());
    }

    @SuppressWarnings("unchecked")
    private void stubChatClient() {
        stubChatClientWithContent(Flux.empty());
    }

    @SuppressWarnings("unchecked")
    private void stubChatClientWithContent(Flux<String> contentFlux) {
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
        when(streamSpec.content()).thenReturn(contentFlux);
    }
}
