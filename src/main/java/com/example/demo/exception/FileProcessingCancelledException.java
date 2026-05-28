package com.example.demo.exception;

public class FileProcessingCancelledException extends RuntimeException {

    public FileProcessingCancelledException(String message) {
        super(message);
    }
}
