-- 为 rag_unit 表添加 filename 字段并建立索引
-- 执行此脚本以优化删除操作性能

-- 1. 添加 filename 字段
ALTER TABLE rag_unit
ADD COLUMN filename VARCHAR(512) COMMENT '原始文件名（用于快速查询和删除）'
AFTER source_id;

-- 2. 从现有数据中提取 filename（从 minio_path 中提取）
UPDATE rag_unit
SET filename = SUBSTRING_INDEX(minio_path, '/', -1)
WHERE filename IS NULL AND minio_path IS NOT NULL;

-- 3. 为 filename 创建索引（提升查询性能）
CREATE INDEX idx_filename ON rag_unit(filename);

-- 4. 为 source_id 创建索引（提升关联查询性能）
CREATE INDEX idx_source_id ON rag_unit(source_id);

-- 验证索引创建成功
SHOW INDEX FROM rag_unit;
