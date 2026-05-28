package com.example.demo.service.processor;

import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import com.example.demo.service.MarkdownStructureChunker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TextProcessorTest {

    @Mock
    private MinerUClient minerUClient;

    @Mock
    private MarkdownStructureChunker markdownStructureChunker;

    @Test
    void shouldUseMineruMarkdownWhenUrlIsProvided() {
        TextProcessor processor = new TextProcessor(minerUClient, markdownStructureChunker);
        RagUnit unit = new RagUnit();
        unit.setSourceType(SourceType.TEXT);
        unit.setContent("概览\n第一段");
        unit.setChunkIndex(0);

        when(minerUClient.extractText("http://example.com/demo.txt", "demo.txt"))
                .thenReturn("# 概览\n\n第一段");
        when(markdownStructureChunker.chunk("# 概览\n\n第一段", "demo.txt", SourceType.TEXT))
                .thenReturn(List.of(unit));

        List<RagUnit> units = processor.process(
                new ByteArrayInputStream("raw text".getBytes(StandardCharsets.UTF_8)),
                "demo.txt",
                "text/plain",
                "http://example.com/demo.txt"
        );

        assertEquals(1, units.size());
        assertEquals("概览\n第一段", units.get(0).getContent());
    }

    @Test
    void shouldFallbackToLocalTextWhenMineruFails() {
        TextProcessor processor = new TextProcessor(minerUClient, markdownStructureChunker);
        RagUnit unit = new RagUnit();
        unit.setSourceType(SourceType.TEXT);
        unit.setContent("本地文本");
        unit.setChunkIndex(0);

        when(minerUClient.extractText("http://example.com/demo.txt", "demo.txt"))
                .thenThrow(new RuntimeException("mineru down"));
        when(markdownStructureChunker.chunk("本地文本", "demo.txt", SourceType.TEXT))
                .thenReturn(List.of(unit));

        List<RagUnit> units = processor.process(
                new ByteArrayInputStream("本地文本".getBytes(StandardCharsets.UTF_8)),
                "demo.txt",
                "text/plain",
                "http://example.com/demo.txt"
        );

        verify(markdownStructureChunker).chunk("本地文本", "demo.txt", SourceType.TEXT);
        assertEquals(1, units.size());
    }
}
