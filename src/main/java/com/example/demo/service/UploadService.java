package com.example.demo.service;

import io.minio.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class UploadService {

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucketName;

    /**
     * Upload file to MinIO
     * @param inputStream File content stream
     * @param fileName Target filename
     * @param contentType MIME type
     * @return Path in bucket
     */
    public String uploadFile(InputStream inputStream, String fileName, String contentType) {
        try {
            ensureBucketExists();

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(fileName)
                            .stream(inputStream, -1, 10485760)
                            .contentType(contentType)
                            .build()
            );

            log.info("File uploaded successfully: {}", fileName);
            return fileName;
        } catch (Exception e) {
            log.error("Error uploading file to MinIO", e);
            throw new RuntimeException("Failed to upload file to MinIO", e);
        }
    }

    /**
     * Upload MultipartFile to MinIO
     */
    public String uploadFile(MultipartFile file, String fileName) {
        try {
            return uploadFile(file.getInputStream(), fileName, file.getContentType());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read multipart file", e);
        }
    }

    /**
     * Get presigned URL for file access
     */
    public String getFileUrl(String fileName) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(fileName)
                            .expiry(7, TimeUnit.DAYS)
                            .build()
            );
        } catch (Exception e) {
            log.error("Error getting file URL", e);
            return "";
        }
    }

    /**
     * Delete file from MinIO
     * @param fileName Path in bucket
     */
    public void deleteFile(String fileName) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(fileName)
                            .build()
            );
            log.info("File deleted from MinIO: {}", fileName);
        } catch (Exception e) {
            log.error("Error deleting file from MinIO", e);
            throw new RuntimeException("Failed to delete file from MinIO", e);
        }
    }

    private void ensureBucketExists() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Bucket created: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("Error checking/creating bucket", e);
            throw new RuntimeException("MinIO bucket error", e);
        }
    }
}
