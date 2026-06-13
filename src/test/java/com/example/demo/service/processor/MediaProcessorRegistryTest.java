package com.example.demo.service.processor;

import com.example.demo.model.RagUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
class MediaProcessorRegistryTest {

    private final MediaProcessor imageProcessor = new StubProcessor("image/*");
    private final MediaProcessor audioProcessor = new StubProcessor("audio/*");

    @Test
    void findByMimeType_returnsMatchingProcessor() {
        MediaProcessorRegistry registry = new MediaProcessorRegistry(List.of(imageProcessor, audioProcessor));

        MediaProcessor result = registry.findByMimeType("image/png");

        assertNotNull(result);
        assertEquals(imageProcessor, result);
    }

    @Test
    void findByMimeType_returnsNullWhenNoneMatch() {
        MediaProcessorRegistry registry = new MediaProcessorRegistry(List.of(imageProcessor, audioProcessor));

        MediaProcessor result = registry.findByMimeType("application/pdf");

        assertNull(result);
    }

    @Test
    void findByMimeType_returnsNullForNullOrBlank() {
        MediaProcessorRegistry registry = new MediaProcessorRegistry(List.of(imageProcessor, audioProcessor));

        assertNull(registry.findByMimeType(null));
        assertNull(registry.findByMimeType(""));
        assertNull(registry.findByMimeType("   "));
    }

    /**
     * Stub processor that matches any mime type containing its prefix pattern.
     */
    private static class StubProcessor implements MediaProcessor {
        private final String pattern;

        StubProcessor(String pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean supports(String mimeType) {
            // Match "image/*" against "image/png", "image/jpeg", etc.
            String prefix = pattern.replace("/*", "/");
            return mimeType != null && mimeType.startsWith(prefix);
        }

        @Override
        public List<RagUnit> process(InputStream input, String filename, String mimeType) {
            return List.of();
        }
    }
}
