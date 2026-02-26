-- RAG Knowledge Base Schema (Combined V1 + V2 + V3)

CREATE DATABASE IF NOT EXISTS rag_knowledge DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE rag_knowledge;

CREATE TABLE IF NOT EXISTS rag_unit (
    id VARCHAR(36) PRIMARY KEY COMMENT '主键ID',
    source_id VARCHAR(36) NOT NULL COMMENT '源文件ID',
    file_hash VARCHAR(64) COMMENT '文件SHA-256哈希值（用于去重）',
    filename VARCHAR(512) COMMENT '原始文件名',
    source_type VARCHAR(20) NOT NULL COMMENT '源类型',
    content TEXT COMMENT '切片内容',
    minio_path VARCHAR(500) COMMENT 'MinIO路径',
    minio_url VARCHAR(1000) COMMENT 'MinIO URL',
    chunk_index INT COMMENT '切片索引',
    start_time BIGINT COMMENT '音视频开始时间',
    end_time BIGINT COMMENT '音视频结束时间',
    metadata TEXT COMMENT '元数据JSON',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引优化
    INDEX idx_source_id (source_id),
    INDEX idx_file_hash (file_hash),
    INDEX idx_filename (filename),
    INDEX idx_source_type (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG切片存储表';
