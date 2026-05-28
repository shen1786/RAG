package com.example.demo.service;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.example.demo.Config.HierarchyConfig;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.dto.RetrievalMode;
import com.example.demo.model.dto.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceTest {

    @Mock
    private VectorStore leafVectorStore;

    @Mock
    private VectorStore summaryVectorStore;

    @Mock
    private RerankModel rerankModel;

    @Mock
    private RagUnitMapper ragUnitMapper;

    @Mock
    private RagUnitService ragUnitService;

    private RagRetrievalService ragRetrievalService;

    @BeforeEach
    void setUp() {
        HierarchyConfig hierarchyConfig = new HierarchyConfig();
        hierarchyConfig.setSummaryCandidateTopK(12);
        hierarchyConfig.setMidRerankTopK(4);
        hierarchyConfig.setLeafRerankTopK(8);

        ragRetrievalService = new RagRetrievalService(
                leafVectorStore,
                summaryVectorStore,
                rerankModel,
                ragUnitMapper,
                ragUnitService,
                hierarchyConfig,
                Runnable::run
        );

        ReflectionTestUtils.setField(ragRetrievalService, "candidateTopK", 15);
        ReflectionTestUtils.setField(ragRetrievalService, "similarityThreshold", 0.3d);
        ReflectionTestUtils.setField(ragRetrievalService, "finalTopK", 5);
        ReflectionTestUtils.setField(ragRetrievalService, "hitScoreThreshold", 0.35d);
    }

    @Test
    void shouldMergeCandidatesFromMultiQueriesAndRerankByPrimaryQuery() {
        Document shared = doc("shared", "shared chunk", 0.85);
        Document originalOnly = doc("original-only", "chunk from original query", 0.62);
        Document rewrittenOnly = doc("rewritten-only", "chunk from rewritten query", 0.91);
        Document relationOnly = doc("relation-only", "chunk from relation query", 0.77);

        when(summaryVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(leafVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenAnswer(invocation -> {
                    SearchRequest request = invocation.getArgument(0);
                    if ("原始问题".equals(request.getQuery())) {
                        return List.of(shared, originalOnly);
                    }
                    if ("改写后的问题".equals(request.getQuery())) {
                        return List.of(shared, rewrittenOnly);
                    }
                    if ("RagUnitService依赖注入".equals(request.getQuery())) {
                        return List.of(shared, relationOnly);
                    }
                    throw new IllegalArgumentException("unexpected query: " + request.getQuery());
                });
        when(rerankModel.call(any(RerankRequest.class))).thenReturn(new RerankResponse(List.of(
                scored(rewrittenOnly, 0.97),
                scored(shared, 0.88),
                scored(originalOnly, 0.74),
                scored(relationOnly, 0.71)
        )));

        RetrievalResult result = ragRetrievalService.retrieveWithMultiPathRecall(
                "改写后的问题",
                List.of("原始问题", "改写后的问题", "RagUnitService依赖注入"),
                "u1"
        );

        ArgumentCaptor<RerankRequest> requestCaptor = ArgumentCaptor.forClass(RerankRequest.class);
        verify(rerankModel, atLeastOnce()).call(requestCaptor.capture());
        List<RerankRequest> capturedRequests = requestCaptor.getAllValues();
        RerankRequest rerankRequest = capturedRequests.get(capturedRequests.size() - 1);

        assertEquals("改写后的问题", rerankRequest.getQuery());
        assertEquals(4, rerankRequest.getInstructions().size());
        assertIterableEquals(
                List.of("shared", "rewritten-only", "original-only", "relation-only"),
                rerankRequest.getInstructions().stream().map(Document::getId).toList()
        );

        assertTrue(result.isHit());
        assertEquals(RetrievalMode.FLAT_FALLBACK, result.getRetrievalMode());
        assertEquals(4, result.getCandidateCount());
        assertEquals(4, result.getFinalCount());
        assertIterableEquals(
                List.of("rewritten-only", "shared", "original-only", "relation-only"),
                result.getDocuments().stream().map(Document::getId).toList()
        );
        assertTrue(result.getKnowledgeText().contains("chunk from rewritten query"));
        assertTrue(result.getKnowledgeText().contains("shared chunk"));
        assertTrue(result.getKnowledgeText().contains("chunk from original query"));
        assertTrue(result.getKnowledgeText().contains("chunk from relation query"));
    }

    @Test
    void shouldSkipDuplicateQueriesAndReturnEmptyWhenNoCandidateFound() {
        when(summaryVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(leafVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RetrievalResult result = ragRetrievalService.retrieveWithMultiPathRecall(
                "同一个问题",
                List.of("同一个问题", "同一个问题", " "),
                "u1"
        );

        verify(leafVectorStore).similaritySearch(any(SearchRequest.class));
        verify(rerankModel, never()).call(any(RerankRequest.class));

        assertFalse(result.isHit());
        assertEquals(0, result.getCandidateCount());
        assertEquals(0, result.getFinalCount());
        assertTrue(result.getDocuments().isEmpty());
    }

    private static Document doc(String id, String text, double score) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("score", score);
        return Document.builder()
                .id(id)
                .text(text)
                .metadata(metadata)
                .score(score)
                .build();
    }

    private static DocumentWithScore scored(Document document, double score) {
        DocumentWithScore result = new DocumentWithScore();
        result.setDocument(document);
        result.setScore(score);
        return result;
    }
}
