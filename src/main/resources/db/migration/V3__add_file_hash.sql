-- 为 rag_unit 表添加 file_hash 字段并建立唯一索引
-- 用于文件去重和快速查找

-- 1. 添加 file_hash 字段
ALTER TABLE rag_unit
ADD COLUMN file_hash VARCHAR(64) COMMENT '文件SHA-256哈希值（用于去重）'
AFTER source_id;

-- 2. 为 file_hash 创建索引（用于快速查找是否已存在）
CREATE INDEX idx_file_hash ON rag_unit(file_hash);

-- 3. 为 source_id 添加索引（用于关联查询同一文件的所有切片）
CREATE INDEX idx_source_id ON rag_unit(source_id);

-- 验证索引创建成功
SHOW INDEX FROM rag_unit;
