package com.example.demo.service;

import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownStructureChunkerTest {

    private MarkdownStructureChunker chunker;

    @BeforeEach
    void setUp() {
        TextSplitterService textSplitterService = new TextSplitterService();
        ReflectionTestUtils.setField(textSplitterService, "defaultChunkSize", 800);
        ReflectionTestUtils.setField(textSplitterService, "minChunkSizeChars", 350);
        ReflectionTestUtils.setField(textSplitterService, "minChunkLengthToEmbed", 5);
        ReflectionTestUtils.setField(textSplitterService, "maxNumChunks", 10000);
        ReflectionTestUtils.setField(textSplitterService, "keepSeparator", true);

        chunker = new MarkdownStructureChunker(textSplitterService);
        ReflectionTestUtils.setField(chunker, "maxCharsPerChunk", 80);
    }

    @Test
    void shouldCreateParagraphChunksAndCarryHeadingContext() {
        String markdown = """
                # 概览

                这是第一段，介绍系统目标。

                ## 细节

                这是第二段，描述实现细节。
                """;

        List<RagUnit> units = chunker.chunk(markdown, "demo.md", SourceType.TEXT);

        assertEquals(2, units.size());
        assertEquals("概览", units.get(0).getTitle());
        assertTrue(units.get(0).getContent().contains("这是第一段"));
        assertEquals("概览 / 细节", units.get(1).getTitle());
        assertTrue(units.get(1).getContent().contains("这是第二段"));
        assertEquals(0, units.get(0).getChunkIndex());
        assertEquals(1, units.get(1).getChunkIndex());
    }

    @Test
    void shouldSplitLongParagraphBySentenceBoundary() {
        ReflectionTestUtils.setField(chunker, "maxCharsPerChunk", 24);

        String markdown = """
                # 说明

                第一段第一句比较长。第一段第二句也比较长。第一段第三句继续补充。
                """;

        List<RagUnit> units = chunker.chunk(markdown, "demo.md", SourceType.TEXT);

        assertEquals(3, units.size());
        assertTrue(units.get(0).getContent().contains("第一段第一句比较长。"));
        assertTrue(units.get(1).getContent().contains("第一段第二句也比较长。"));
        assertTrue(units.get(2).getContent().contains("第一段第三句继续补充。"));
    }
}
