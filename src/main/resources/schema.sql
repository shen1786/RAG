-- RAG Knowledge Base Schema

CREATE DATABASE IF NOT EXISTS rag_knowledge DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE rag_knowledge;

CREATE TABLE IF NOT EXISTS rag_unit (
    id VARCHAR(36) PRIMARY KEY,
    source_id VARCHAR(36) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    content TEXT,
    minio_path VARCHAR(500),
    minio_url VARCHAR(1000),
    chunk_index INT,
    start_time BIGINT,
    end_time BIGINT,
    metadata TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_source_id (source_id),
    INDEX idx_source_type (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
