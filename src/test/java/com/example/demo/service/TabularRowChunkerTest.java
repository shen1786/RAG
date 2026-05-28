package com.example.demo.service;

import com.example.demo.model.RagUnit;
import com.example.demo.model.SourceType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabularRowChunkerTest {

    private final TabularRowChunker chunker = new TabularRowChunker();

    @Test
    void shouldCreateOneChunkPerCsvRow() {
        String csv = """
                name,age,city
                Alice,30,Shanghai
                Bob,28,Shenzhen
                """;

        List<RagUnit> units = chunker.chunkCsv(new ByteArrayInputStream(csv.getBytes()), "people.csv", SourceType.TEXT);

        assertEquals(2, units.size());
        assertEquals("people.csv Row 2", units.get(0).getTitle());
        assertTrue(units.get(0).getContent().contains("name: Alice"));
        assertTrue(units.get(0).getContent().contains("age: 30"));
        assertEquals("people.csv Row 3", units.get(1).getTitle());
        assertTrue(units.get(1).getContent().contains("city: Shenzhen"));
    }

    @Test
    void shouldCreateOneChunkPerExcelRow() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Users");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("name");
            header.createCell(1).setCellValue("email");

            var row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("Alice");
            row1.createCell(1).setCellValue("alice@example.com");

            var row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("Bob");
            row2.createCell(1).setCellValue("bob@example.com");

            workbook.write(output);
            bytes = output.toByteArray();
        }

        List<RagUnit> units = chunker.chunkWorkbook(
                new ByteArrayInputStream(bytes),
                "users.xlsx",
                SourceType.TEXT
        );

        assertEquals(2, units.size());
        assertEquals("Users Row 2", units.get(0).getTitle());
        assertTrue(units.get(0).getContent().contains("name: Alice"));
        assertTrue(units.get(0).getContent().contains("email: alice@example.com"));
        assertEquals("Users Row 3", units.get(1).getTitle());
        assertTrue(units.get(1).getContent().contains("name: Bob"));
    }
}
