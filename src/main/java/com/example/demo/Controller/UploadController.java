package com.example.demo.Controller;

import com.example.demo.model.dto.UploadResponse;
import com.example.demo.service.RagUnitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
@Slf4j
@CrossOrigin(origins = "*")
public class UploadController {

    @Autowired
    private RagUnitService ragUnitService;

    @PostMapping
    public ResponseEntity<UploadResponse> uploadFile(@RequestParam("file") MultipartFile file) {
        log.info("Received file upload request: {}", file.getOriginalFilename());

        try {
            UploadResponse response = ragUnitService.processAndStore(file);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing file upload", e);
            return ResponseEntity.internalServerError()
                    .body(UploadResponse.error("Failed to process file: " + e.getMessage()));
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<?> uploadFiles(@RequestParam("files") MultipartFile[] files) {
        log.info("Received batch upload request: {} files", files.length);

        try {
            var responses = new java.util.ArrayList<UploadResponse>();
            for (MultipartFile file : files) {
                responses.add(ragUnitService.processAndStore(file));
            }
            return ResponseEntity.ok(responses);
        } catch (Exception e) {
            log.error("Error processing batch upload", e);
            return ResponseEntity.internalServerError()
                    .body(UploadResponse.error("Failed to process files: " + e.getMessage()));
        }
    }
}
