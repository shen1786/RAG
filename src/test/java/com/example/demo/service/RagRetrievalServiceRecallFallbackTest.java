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
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceRecallFallbackTest {

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
    void shouldFallbackToKeywordCandidatesWhenVectorRecallMisses() {
        RagUnit keywordUnit = leafUnit(
                "leaf-1",
                "中医临床诊疗术语 第1部分：疾病 / 鼻病类 / 鼻鼽",
                "鼻鼽 allergic rhinitis 因肺气虚寒，卫表不固，邪气干鼻所致。",
                0
        );
        Document rerankedDocument = Document.builder()
                .id("leaf-1")
                .text(keywordUnit.getContent())
                .metadata(Map.of("user_id", "u1"))
                .score(0.93)
                .build();

        when(summaryVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(leafVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(ragUnitQueryRepository.searchLeafUnitsByKeyword(any(), any(), any(int.class))).thenReturn(List.of(keywordUnit));
        when(ragUnitQueryRepository.selectByIds(anyList())).thenReturn(List.of(keywordUnit));
        when(ragUnitService.buildVectorMetadata(any(RagUnit.class), any(String.class)))
                .thenReturn(Map.of("user_id", "u1"));
        when(rerankModel.call(any(RerankRequest.class))).thenReturn(new RerankResponse(List.of(
                scored(rerankedDocument, 0.93)
        )));

        RetrievalResult result = ragRetrievalService.retrieveWithMultiPathRecall(
                "鼻鼽是什么",
                List.of("鼻鼽"),
                "u1"
        );

        verify(ragUnitQueryRepository, atLeastOnce()).searchLeafUnitsByKeyword(any(), any(), any(int.class));
        verify(rerankModel).call(any(RerankRequest.class));

        assertTrue(result.isHit());
        assertEquals(RetrievalMode.FLAT_FALLBACK, result.getRetrievalMode());
        assertEquals(1, result.getCandidateCount());
        assertEquals(1, result.getFinalCount());
        assertEquals("leaf-1", result.getDocuments().get(0).getId());
        assertTrue(result.getKnowledgeText().contains("鼻鼽"));
    }

    @Test
    void shouldReturnPrimaryResultWhenMultiPathRecallFindsNothing() {
        RagRetrievalService spyService = Mockito.spy(ragRetrievalService);
        RetrievalResult primaryResult = RetrievalResult.builder()
                .documents(List.of(Document.builder()
                        .id("primary-leaf")
                        .text("鼻鼽 allergic rhinitis")
                        .metadata(Map.of("user_id", "u1"))
                        .score(0.88)
                        .build()))
                .hit(true)
                .retrievalMode(RetrievalMode.HIERARCHICAL)
                .knowledgeText("鼻鼽 allergic rhinitis")
                .candidateCount(2)
                .finalCount(1)
                .durationMs(12L)
                .hierarchyHits(List.of())
                .build();

        when(leafVectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        doReturn(primaryResult).when(spyService).retrieve("鼻鼽是什么", "u1", 5, 0.35d);

        RetrievalResult result = spyService.retrieveWithMultiPathRecall(
                "鼻鼽是什么",
                List.of("鼻鼽 定义"),
                "u1"
        );

        assertSame(primaryResult, result);
    }

    private static RagUnit leafUnit(String id, String title, String content, int chunkIndex) {
        RagUnit unit = new RagUnit();
        unit.setId(id);
        unit.setTitle(title);
        unit.setContent(content);
        unit.setChunkIndex(chunkIndex);
        unit.setFilename("中医临床诊疗术语 第1部分：疾病.docx");
        unit.setSourceType(SourceType.TEXT);
        unit.setNodeType(RagNodeType.LEAF);
        unit.setUserId("u1");
        unit.setSourceId("source-1");
        return unit;
    }

    private static DocumentWithScore scored(Document document, double score) {
        DocumentWithScore result = new DocumentWithScore();
        result.setDocument(document);
        result.setScore(score);
        return result;
    }
}
