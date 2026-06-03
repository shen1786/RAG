DROP DATABASE IF EXISTS rag_knowledge;
SOURCE /root/schema.sql;
USE rag_knowledge;
SHOW TABLES;
SELECT COUNT(*) AS auth_user_count FROM auth_user;
SELECT COUNT(*) AS rag_unit_count FROM rag_unit;
SELECT COUNT(*) AS document_file_count FROM document_file;
