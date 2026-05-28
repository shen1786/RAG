package com.example.demo.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSchemaMigrationService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureEmailColumnExists() {
        try {
            Integer emailColumnCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'auth_user' AND COLUMN_NAME = 'email'",
                    Integer.class
            );
            if (emailColumnCount != null && emailColumnCount == 0) {
                jdbcTemplate.execute("ALTER TABLE auth_user ADD COLUMN email VARCHAR(255) NULL COMMENT '邮箱' AFTER username");
                log.info("已为 auth_user 表补充 email 列");
            }

            Integer emailIndexCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'auth_user' AND INDEX_NAME = 'uk_auth_user_email'",
                    Integer.class
            );
            if (emailIndexCount != null && emailIndexCount == 0) {
                jdbcTemplate.execute("ALTER TABLE auth_user ADD UNIQUE KEY uk_auth_user_email (email)");
                log.info("已为 auth_user 表补充邮箱唯一索引");
            }
        } catch (Exception ex) {
            log.warn("校验 auth_user 邮箱字段时出现异常，可能需要手工迁移数据库", ex);
        }
    }
}
