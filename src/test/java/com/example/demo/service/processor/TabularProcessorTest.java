package com.example.demo.service.processor;

import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import com.example.demo.service.TabularRowChunker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TabularProcessorTest {

    @Mock
    private TabularRowChunker tabularRowChunker;

    @Test
    void shouldDelegateCsvToRowChunker() {
        TabularProcessor processor = new TabularProcessor(tabularRowChunker);
        RagUnit unit = new RagUnit();
        unit.setSourceType(SourceType.TEXT);
        unit.setContent("name: Alice");
        unit.setChunkIndex(0);

        when(tabularRowChunker.chunkCsv(any(), eq("demo.csv"), eq(SourceType.TEXT)))
                .thenReturn(List.of(unit));

        List<RagUnit> result = processor.process(
                new ByteArrayInputStream("name\nAlice".getBytes()),
                "demo.csv",
                "text/csv"
        );

        verify(tabularRowChunker).chunkCsv(any(), eq("demo.csv"), eq(SourceType.TEXT));
        assertEquals(1, result.size());
    }

    @Test
    void shouldDelegateExcelToRowChunker() {
        TabularProcessor processor = new TabularProcessor(tabularRowChunker);
        RagUnit unit = new RagUnit();
        unit.setSourceType(SourceType.TEXT);
        unit.setContent("name: Alice");
        unit.setChunkIndex(0);

        when(tabularRowChunker.chunkWorkbook(any(), eq("demo.xlsx"), eq(SourceType.TEXT)))
                .thenReturn(List.of(unit));

        List<RagUnit> result = processor.process(
                new ByteArrayInputStream(new byte[0]),
                "demo.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );

        verify(tabularRowChunker).chunkWorkbook(any(), eq("demo.xlsx"), eq(SourceType.TEXT));
        assertEquals(1, result.size());
    }
}
