-- V2: Add email column and unique index to auth_user (idempotent, MySQL compatible)

-- Add column if not exists
SET @schema = DATABASE();
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = 'auth_user' AND COLUMN_NAME = 'email'
);

SET @sql = IF(@col_exists = 0,
  'ALTER TABLE auth_user ADD COLUMN email VARCHAR(255) NULL COMMENT ''邮箱'' AFTER username',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add unique index if not exists
SET @idx_exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = 'auth_user' AND INDEX_NAME = 'uk_auth_user_email'
);

SET @sql = IF(@idx_exists = 0,
  'ALTER TABLE auth_user ADD UNIQUE INDEX uk_auth_user_email (email)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
