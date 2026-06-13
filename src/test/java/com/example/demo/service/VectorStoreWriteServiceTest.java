package com.example.demo.service;

import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorStoreWriteServiceTest {

    @Mock
    private VectorStore leafVectorStore;

    @Mock
    private VectorStore summaryVectorStore;

    @Mock
    private RagUnitService ragUnitService;

    private VectorStoreWriteService vectorStoreWriteService;

    @BeforeEach
    void setUp() {
        vectorStoreWriteService = new VectorStoreWriteService(leafVectorStore, summaryVectorStore, ragUnitService);
    }

    @Test
    void shouldFallbackToSingleDocumentWritesAfterBatchRetriesExhausted() {
        List<RagUnit> units = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            units.add(leafUnit("leaf-" + i));
        }

        when(ragUnitService.buildVectorMetadata(any(RagUnit.class), any(String.class)))
                .thenReturn(Map.of("user_id", "u1"));

        doThrow(new RuntimeException("batch failed"))
                .when(leafVectorStore)
                .add(argThat(documents -> documents != null && documents.size() > 1));
        doNothing()
                .when(leafVectorStore)
                .add(argThat(documents -> documents != null && documents.size() == 1));

        vectorStoreWriteService.addUnitsToVectorStores(units, "sample.docx");

        verify(leafVectorStore, times(3)).add(argThat(documents -> documents != null && documents.size() == 3));
        verify(leafVectorStore).add(argThat(documents -> hasSingleDocumentId(documents, "leaf-0")));
        verify(leafVectorStore).add(argThat(documents -> hasSingleDocumentId(documents, "leaf-1")));
        verify(leafVectorStore).add(argThat(documents -> hasSingleDocumentId(documents, "leaf-2")));
    }

    @Test
    void shouldRouteLeafAndSummaryToDifferentStores() {
        RagUnit leaf = leafUnit("leaf-1");
        RagUnit summary = summaryUnit("summary-1");

        when(ragUnitService.buildVectorMetadata(any(RagUnit.class), any(String.class)))
                .thenReturn(Map.of("user_id", "u1"));

        vectorStoreWriteService.addUnitsToVectorStores(List.of(leaf, summary), "test.docx");

        verify(leafVectorStore).add(argThat(docs -> docs.size() == 1 && "leaf-1".equals(docs.get(0).getId())));
        verify(summaryVectorStore).add(argThat(docs -> docs.size() == 1 && "summary-1".equals(docs.get(0).getId())));
    }

    @Test
    void shouldDeleteFromBothStores() {
        vectorStoreWriteService.deleteFromVectorStores(List.of("id-1", "id-2"));

        verify(leafVectorStore).delete(List.of("id-1", "id-2"));
        verify(summaryVectorStore).delete(List.of("id-1", "id-2"));
    }

    private static boolean hasSingleDocumentId(List<org.springframework.ai.document.Document> documents, String expectedId) {
        return documents.size() == 1 && expectedId.equals(documents.get(0).getId());
    }

    private static RagUnit leafUnit(String id) {
        RagUnit unit = new RagUnit();
        unit.setId(id);
        unit.setSourceId("source-1");
        unit.setSourceType(SourceType.TEXT);
        unit.setNodeType(RagNodeType.LEAF);
        unit.setContent("content of " + id);
        unit.setChunkIndex(0);
        unit.setUserId("u1");
        unit.setFilename("test.docx");
        return unit;
    }

    private static RagUnit summaryUnit(String id) {
        RagUnit unit = new RagUnit();
        unit.setId(id);
        unit.setSourceId("source-1");
        unit.setSourceType(SourceType.TEXT);
        unit.setNodeType(RagNodeType.SECTION_SUMMARY);
        unit.setContent("summary of " + id);
        unit.setChunkIndex(0);
        unit.setUserId("u1");
        unit.setFilename("test.docx");
        return unit;
    }
}
