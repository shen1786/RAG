package com.example.demo.service;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.example.demo.Config.HierarchyConfig;
import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import com.example.demo.model.dto.RetrievalMode;
import com.example.demo.model.dto.RetrievalResult;
import com.example.demo.repository.RagUnitQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.ai.vectorstore.redis.RedisFilterExpressionConverter;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
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
import static org.mockito.ArgumentMatchers.anyList;
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
    private RagUnitQueryRepository ragUnitQueryRepository;

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
                ragUnitQueryRepository,
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

    @Test
    void shouldExpandNeighborLeavesIntoKnowledgeTextAfterRerank() {
        Document target = doc("leaf-2", "1.编程实现“第二日”问题，为后续的测试做准备。", 0.91);
        RagUnit title = leafUnit("leaf-1", "实验一 黑盒测试（1） 一、实验目的：", 1);
        RagUnit matched = leafUnit("leaf-2", "1.编程实现“第二日”问题，为后续的测试做准备。", 2);
        RagUnit next = leafUnit("leaf-3", "2.通过“第二日”问题的等价类划分，掌握等价类方法及测试用例的设计。", 3);

        when(summaryVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(leafVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(target));
        when(rerankModel.call(any(RerankRequest.class))).thenReturn(new RerankResponse(List.of(
                scored(target, 0.91)
        )));
        when(ragUnitQueryRepository.selectByIds(anyList())).thenReturn(List.of(matched));
        when(ragUnitQueryRepository.selectNeighborLeaves(any(), any(int.class), any(int.class))).thenReturn(List.of(title, matched, next));

        RetrievalResult result = ragRetrievalService.retrieveWithMultiPathRecall(
                "实验一中第二日问题的目的是什么？",
                List.of("第二日问题 实验目的"),
                "u1"
        );

        assertTrue(result.isHit());
        assertTrue(result.getKnowledgeText().contains("实验一 黑盒测试（1） 一、实验目的"));
        assertTrue(result.getKnowledgeText().contains("1.编程实现“第二日”问题"));
        assertTrue(result.getKnowledgeText().contains("2.通过“第二日”问题的等价类划分"));
    }

    @Test
    void shouldEscapeUserIdInRedisTagFilterExpression() {
        String userId = "fb732c63-44f7-4666-b884-f6524298afe0";

        String filterExpression = ReflectionTestUtils.invokeMethod(
                ragRetrievalService,
                "buildUserFilterExpression",
                userId
        );

        assertEquals(
                "user_id == 'fb732c63\\-44f7\\-4666\\-b884\\-f6524298afe0'",
                filterExpression
        );

        FilterExpressionTextParser parser = new FilterExpressionTextParser();
        RedisFilterExpressionConverter converter = new RedisFilterExpressionConverter(
                List.of(RedisVectorStore.MetadataField.tag("user_id"))
        );

        String nativeExpression = converter.convertExpression(parser.parse(filterExpression));

        assertEquals(
                "@user_id:{fb732c63\\-44f7\\-4666\\-b884\\-f6524298afe0}",
                nativeExpression
        );
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

    private static RagUnit leafUnit(String id, String content, int chunkIndex) {
        RagUnit unit = new RagUnit();
        unit.setId(id);
        unit.setSourceId("source-1");
        unit.setFileHash("hash-1");
        unit.setUserId("u1");
        unit.setFilename("3-软件测试技术-实验指导书-0515.doc");
        unit.setSourceType(SourceType.TEXT);
        unit.setNodeType(RagNodeType.LEAF);
        unit.setContent(content);
        unit.setChunkIndex(chunkIndex);
        unit.setTreeLevel(0);
        return unit;
    }
}
