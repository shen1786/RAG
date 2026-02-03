package com.example.demo.service.processor;

import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class TextProcessor implements MediaProcessor {

    @Value("${chunking.fixed.chunk-size}")
    private int chunkSize;

    @Value("${chunking.fixed.overlap}")
    private int overlap;

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && (
                mimeType.startsWith("text/") ||
                mimeType.equals("application/pdf") ||
                mimeType.equals("application/msword") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        );
    }

    @Override
    public List<RagUnit> process(InputStream input, String filename, String mimeType) {
        log.info("Processing text file: {}", filename);
        List<RagUnit> units = new ArrayList<>();

        try {
            // Extract text using Tika
            Parser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1); // No limit
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();

            parser.parse(input, handler, metadata, context);
            String fullText = handler.toString();

            // Chunk text
            List<String> chunks = chunkText(fullText);
            String sourceId = UUID.randomUUID().toString(); // Or passed from upload service

            for (int i = 0; i < chunks.size(); i++) {
                RagUnit unit = new RagUnit();
                unit.setSourceType(SourceType.TEXT);
                unit.setContent(chunks.get(i));
                unit.setChunkIndex(i);
                unit.setSourceId(sourceId); // Note: This should ideally link to the uploaded file ID
                // unit.setMinioPath(); // Set by caller or here if we handle upload here

                units.add(unit);
            }

        } catch (Exception e) {
            log.error("Error processing text file", e);
            throw new RuntimeException("Failed to process text file", e);
        }

        return units;
    }

    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        int length = text.length();
        int start = 0;

        while (start < length) {
            int end = Math.min(start + chunkSize, length);
            chunks.add(text.substring(start, end));

            if (end == length) break;

            start += (chunkSize - overlap);
        }

        return chunks;
    }
}
