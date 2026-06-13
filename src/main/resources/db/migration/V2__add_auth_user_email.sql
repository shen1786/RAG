-- V2: Add email column and unique index to auth_user (idempotent)
-- Uses MySQL 8.0+ IF NOT EXISTS syntax (compatible with Flyway)
ALTER TABLE auth_user
  ADD COLUMN IF NOT EXISTS email VARCHAR(255) NULL COMMENT '邮箱' AFTER username;

ALTER TABLE auth_user
  ADD UNIQUE INDEX IF NOT EXISTS uk_auth_user_email (email);