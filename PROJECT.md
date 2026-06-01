# RAG 知识库系统

基于 Spring Boot 3 + Spring AI 的企业级 RAG（检索增强生成）知识库系统，支持多格式文档上传、智能切片、向量化存储和 AI 问答。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.3.5 + Spring AI 1.0.0 |
| 大模型 | 阿里云 DashScope（通义千问 + text-embedding-v3） |
| 向量存储 | Redis（Spring AI Redis VectorStore） |
| 关系数据库 | MySQL 8.0 + MyBatis-Plus 3.5.7 |
| 消息队列 | RabbitMQ（异步文件处理） |
| 对象存储 | MinIO（文件存储） |
| 文档解析 | MinerU（云端精准解析）+ Apache Tika（本地兜底） |
| 前端 | Vue 3 + Tailwind CSS |
| 认证 | Sa-Token |

## 核心功能

- **多格式文档处理**：支持 PDF、Word、PPT、Excel、图片、音视频等格式
- **层级索引**：三层摘要树结构（叶子切片 → 章节摘要 → 文档摘要），提升检索质量
- **RAG 检索**：向量粗召回 + Rerank 精排，支持相似度阈值和命中分数控制
- **异步处理**：RabbitMQ 异步消费，支持并发文件处理和手动 ACK
- **SHA-256 去重**：相同文件不重复处理
- **分片上传**：支持大文件分片上传和断点续传

## 项目亮点

### 1. MySQL 批处理优化

将切片入库从逐条单行 INSERT 改为 `ExecutorType.BATCH` 批量写入，配合 `rewriteBatchedStatements=true` 驱动参数，将 1000 条切片的网络往返从 1000 次降至 2 次。

核心实现：
```java
try (SqlSession batchSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
    RagUnitMapper batchMapper = batchSession.getMapper(RagUnitMapper.class);
    for (int i = 0; i < units.size(); i++) {
        batchMapper.insert(units.get(i));
        if ((i + 1) % 500 == 0) batchSession.flushStatements();
    }
    batchSession.flushStatements();
}
```

### 2. 依赖冲突解决

sa-token-redis-jackson 间接引入 Lettuce，与项目使用的 Jedis 冲突，导致 `JedisConnectionFactory` bean 无法自动配置。通过排除 `spring-boot-starter-data-redis` 传递依赖解决。

### 3. MinerU 云端解析 + 本地兜底

MinerU 提供高精度文档解析（公式、表格、复杂排版），失败时自动回退到本地 Tika 解析，保证服务可用性。

### 4. 多服务限流与超时控制

- 阿里云 ASR：重试 2 次、回调超时 5 秒、视频 30 秒切段
- MinerU：连接超时 30 秒、轮询间隔 5 秒、最大等待 10 分钟
- MinIO：10MB 分片流上传、批量上传最多 20 文件
- Spring 全局：单文件 500MB 上传限制

## 开发过程中遇到的问题

### 问题 1：JedisConnectionFactory Bean 缺失

**现象**：启动时报 `No qualifying bean of type 'JedisConnectionFactory'`

**原因**：sa-token-redis-jackson → sa-token-redis-template → spring-boot-starter-data-redis → lettuce-core，Lettuce 和 Jedis 同时在 classpath 上，Spring Boot 默认选 Lettuce

**解决**：在 pom.xml 中排除 sa-token 对 spring-boot-starter-data-redis 的传递依赖

### 问题 2：MinerU ZIP 下载 SSL 握手失败

**现象**：JDK 23 环境下下载 MinerU 结果 ZIP 时 `SSLHandshakeException: Remote host terminated the handshake`

**原因**：JDK 23 的 TLS 实现与 MinerU CDN 服务器不兼容

**解决**：使用 JDK 17 部署，问题消失

### 问题 3：HikariCP 连接池连接失效

**现象**：频繁出现 `Failed to validate connection (No operations allowed after connection closed)`

**原因**：`maxLifetime: 600000`（10 分钟）大于 MySQL 服务端的 `wait_timeout`，连接被服务端关闭后客户端仍持有

**解决**：建议将 `maxLifetime` 调整为 180000（3 分钟），小于 MySQL 的 wait_timeout

### 问题 4：MinIO 地址配置不一致

**现象**：MinIO 连接超时

**原因**：Redis 和 RabbitMQ 迁移到新服务器后，MinIO endpoint 未同步更新

**解决**：确认 MinIO 实际部署位置，统一配置地址

## 部署

```bash
# 打包
mvn package -DskipTests

# 部署到服务器
scp target/demo-0.0.1-SNAPSHOT.jar root@server:/www/wwwroot/

# 启动
nohup java -Xms512m -Xmx1024m -jar demo-0.0.1-SNAPSHOT.jar > app.log 2>&1 &
```
