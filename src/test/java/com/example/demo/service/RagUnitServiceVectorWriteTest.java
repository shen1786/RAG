package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.demo.mapper.RagUnitMapper;
import com.example.demo.model.RagNodeType;
import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import com.example.demo.service.processor.ImageProcessor;
import com.example.demo.service.processor.PowerPointProcessor;
import com.example.demo.service.processor.TabularProcessor;
import com.example.demo.service.processor.TextProcessor;
import com.example.demo.service.processor.VideoProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;

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
class RagUnitServiceVectorWriteTest {

    @Mock
    private RagUnitMapper ragUnitMapper;

    @Mock
    private UploadService uploadService;

    @Mock
    private VectorStore leafVectorStore;

    @Mock
    private VectorStore summaryVectorStore;

    @Mock
    private TextProcessor textProcessor;

    @Mock
    private ImageProcessor imageProcessor;

    @Mock
    private VideoProcessor videoProcessor;

    @Mock
    private PowerPointProcessor powerPointProcessor;

    @Mock
    private com.example.demo.service.processor.PdfProcessor pdfProcessor;

    @Mock
    private com.example.demo.service.processor.WordProcessor wordProcessor;

    @Mock
    private TabularProcessor tabularProcessor;

    @Mock
    private FileProcessProducer fileProcessProducer;

    @Mock
    private DocumentFileService documentFileService;

    @Mock
    private DocumentDeleteService documentDeleteService;

    @Mock
    private HierarchicalIndexingService hierarchicalIndexingService;

    private RagUnitService ragUnitService;

    @BeforeEach
    void setUp() {
        ragUnitService = new RagUnitService(
                ragUnitMapper,
                uploadService,
                leafVectorStore,
                summaryVectorStore,
                textProcessor,
                imageProcessor,
                videoProcessor,
                powerPointProcessor,
                pdfProcessor,
                wordProcessor,
                tabularProcessor,
                fileProcessProducer,
                documentFileService,
                documentDeleteService,
                hierarchicalIndexingService
        );
    }

    @Test
    void shouldFallbackToSingleDocumentWritesAfterBatchRetriesExhausted() {
        List<RagUnit> units = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            units.add(unit("leaf-" + i));
        }

        doThrow(new RuntimeException("batch failed"))
                .when(leafVectorStore)
                .add(argThat(documents -> documents != null && documents.size() > 1));
        doNothing()
                .when(leafVectorStore)
                .add(argThat(documents -> documents != null && documents.size() == 1));

        ragUnitService.addUnitsToVectorStores(units, "sample.docx");

        verify(leafVectorStore, times(3)).add(argThat(documents -> documents != null && documents.size() == 3));
        verify(leafVectorStore).add(argThat(documents -> hasSingleDocumentId(documents, "leaf-0")));
        verify(leafVectorStore).add(argThat(documents -> hasSingleDocumentId(documents, "leaf-1")));
        verify(leafVectorStore).add(argThat(documents -> hasSingleDocumentId(documents, "leaf-2")));
    }

    @Test
    void shouldDeleteFallbackIdsWhenMysqlRowsAreAlreadyRolledBack() {
        when(ragUnitMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ragUnitService.removeIndexedData("source-1", List.of("leaf-1", "summary-1"));

        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(leafVectorStore).delete(idsCaptor.capture());
        verify(summaryVectorStore).delete(idsCaptor.getValue());

        List<String> deletedIds = idsCaptor.getValue();
        assertEquals(2, deletedIds.size());
        assertTrue(deletedIds.contains("leaf-1"));
        assertTrue(deletedIds.contains("summary-1"));

        verify(ragUnitMapper).delete(any(QueryWrapper.class));
    }

    private static boolean hasSingleDocumentId(List<Document> documents, String id) {
        return documents.size() == 1 && id.equals(documents.get(0).getId());
    }

    private static RagUnit unit(String id) {
        RagUnit unit = new RagUnit();
        unit.setId(id);
        unit.setContent("content-" + id);
        unit.setFilename("sample.docx");
        unit.setSourceId("source-1");
        unit.setUserId("user-1");
        unit.setSourceType(SourceType.TEXT);
        unit.setNodeType(RagNodeType.LEAF);
        return unit;
    }
}
