package com.example.demo.model.dto;

import lombok.Data;

@Data
public class UploadRequest {
    private String filename;
    private String description;
}
