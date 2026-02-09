package com.example.demo.service.processor;

import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import com.example.demo.service.TextSplitterService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class TextProcessor implements MediaProcessor {

    @Autowired
    private TextSplitterService textSplitterService;

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && (
                mimeType.startsWith("text/") ||
                mimeType.equals("application/pdf") ||
                // Word documents
                mimeType.equals("application/msword") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document") ||
                // PowerPoint presentations
                mimeType.equals("application/vnd.ms-powerpoint") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation") ||
                // Excel spreadsheets
                mimeType.equals("application/vnd.ms-excel") ||
                mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
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

            // Chunk text using Spring AI TokenTextSplitter
            List<String> chunks = textSplitterService.splitText(fullText);
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
}
