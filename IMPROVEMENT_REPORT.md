# 项目改进报告

> **项目**：Multimodal RAG Knowledge Base System
> **仓库**：https://github.com/cd-zwj/Multimodal-RAG-modification
> **分支**：master
> **时间**：2026-06-12 ~ 2026-06-13
> **提交数**：4 次

---

## 一、改进总览

本轮改进覆盖 **安全加固、可靠性修复、架构重构、开发体验** 四个维度，共涉及 **40+ 文件变更，新增 2500+ 行、删除 1200+ 行**。

| 维度 | 改进项数 | 关键指标 |
|------|---------|---------|
| 🔴 安全加固 | 8 | 密码策略、权限校验、速率限制、XSS 防护 |
| 🟠 可靠性修复 | 6 | 连接泄漏、超时保护、线程池治理、事务保障 |
| 🟡 架构重构 | 10 | God Class 拆分、策略模式、数据访问层独立 |
| 🟢 开发体验 | 4 | Swagger 文档、日志规范、代码去重 |

---

## 二、安全加固

### 2.1 密码安全增强

**文件**：`AuthAccountService.java`

| 改动 | Before | After |
|------|--------|-------|
| 最小长度 | 无限制 | 8 字符 |
| 最大长度 | 无限制 | 128 字符 |
| 复杂度要求 | 仅非空检查 | 必须包含字母 + 数字 |

### 2.2 管理员密码重置权限校验

**文件**：`AuthAccountService.resetPasswordByUsername()`

新增纵深防御：调用 `StpUtil.getLoginIdAsString()` 获取当前用户，通过 `AuthPermissionService.getRoleList()` 验证 `admin` 角色，防止普通用户越权重置他人密码。

### 2.3 密码重置码泄露风险

**文件**：`PasswordRecoveryService.java`

`exposeResetCode` 默认值从 `true` 改为 `false`，避免开发配置泄露到生产环境。

### 2.4 登录端点速率限制

**新增文件**：`RateLimitInterceptor.java`

基于 Redis 的 IP 级固定窗口计数器：
- 每个窗口最大 10 次请求，窗口时长 60 秒
- 覆盖端点：`/auth/login`、`/auth/register`、`/auth/password/forgot/request`
- 支持 `X-Forwarded-For` / `X-Real-IP` 代理头识别真实 IP
- 超限时返回 HTTP 429 + 业务错误码

### 2.5 Controller 层 XSS 防护

| Controller | 防护措施 |
|-----------|---------|
| `AiController.chat` | `msg` 参数长度限制 4000 字符 |
| `RagDocumentController.getDocuments` | `keyword` 截断到 200 字符 |
| `ChunkUploadController.checkUploadStatus` | `filename` 经 `FileNameSanitizer.sanitize()` 清洗 |
| `RagDocumentApplicationService` | `sortBy` / `sortOrder` 白名单校验（`createdAt`/`updatedAt` × `ASC`/`DESC`） |

### 2.6 排序参数注入防护

**文件**：`RagDocumentApplicationService.java`

`sortBy` 仅允许 `createdAt`、`updatedAt`；`sortOrder` 仅允许 `ASC`、`DESC`，非法值静默降级为默认值，防止 SQL 注入。

---

## 三、可靠性修复

### 3.1 HttpURLConnection 连接泄漏

**文件**：`FileProcessConsumer.downloadFromMinio()`

Before：返回 `connection.getInputStream()`，调用方关闭 InputStream 时底层连接未断开。
After：用 `FilterInputStream` 包装，`close()` 时在 `finally` 块中调用 `connection.disconnect()`。

### 3.2 VideoProcessor 超时保护

**文件**：`VideoProcessor.java`

`CompletableFuture.join()`（无限阻塞）→ `get(5, TimeUnit.MINUTES)`，超时后 `cancel(true)` 跳过分片并记录日志，防止线程池耗尽。

### 3.3 线程池治理

| 组件 | Before | After |
|------|--------|-------|
| `HierarchySummaryService` | `newCachedThreadPool`（无界） | `ThreadPoolExecutor(2-8, queue=100, CallerRunsPolicy)` |
| `UserProfileService` | `@Async`（默认池） | `@Async("asyncTaskExecutor")`（指定池） |
| `AsyncConfig` | 不存在（`@EnableAsync` 在 Application 类） | 独立配置类，`asyncTaskExecutor` 带优雅停机（`waitForTasksToCompleteOnShutdown=true`） |

### 3.4 事务保障

| 方法 | 新增注解 |
|------|---------|
| `AuthAccountService.register()` | `@Transactional`（insert user + insert user_role 原子性） |
| `DocumentDeleteService.asyncDeleteDocument()` | `@Transactional`（markDeleted + saveTaskStatus 原子性） |

---

## 四、架构重构

### 4.1 RagUnitService 瘦身（God Class 拆分）

**Before**：630 行，16 个构造器依赖（7 个 MediaProcessor + 2 个 VectorStore + 7 个其他）

**After**：400 行，9 个构造器依赖

| 提取目标 | 职责 | 依赖数 |
|---------|------|--------|
| `MediaProcessorRegistry` | 根据 MIME 类型自动查找处理器 | 1（`List<MediaProcessor>`） |
| `VectorStoreWriteService` | leaf/summary 双索引批量写入 + 重试 + 逐条降级 | 3 |

### 4.2 AiService 拆分

**Before**：303 行，混合聊天编排 + 会话管理

**After**：

| 类 | 行数 | 职责 |
|----|------|------|
| `AiService` | ~218 | 单轮/多轮对话、流式 SSE、引文构建、System Prompt |
| `ChatSessionService` | ~130 | 会话 CRUD、历史记录、画像提炼触发、会话归属校验 |

### 4.3 RagRetrievalService 策略模式拆分

**Before**：792 行，层次检索 + 平铺检索 + 多路召回 + 关键词回退 + rerank + 数据查询全部混在一起

**After**：290 行（编排层）+ 6 个策略/工具类

```
service/retrieval/
├── RetrievalStrategy.java          # 策略接口
├── HierarchicalRetrievalStrategy   # 摘要→Section→叶子层次检索 (~170行)
├── FlatRetrievalStrategy           # 叶子向量库平铺检索 (~90行)
├── KnowledgeTextBuilder            # 知识文本格式化、邻居扩展、父节点查询
├── RerankHelper                    # rerank 模型调用 + 熔断降级
└── UserFilterBuilder               # Redis Tag 过滤表达式构建
```

**策略选择逻辑**：
```
retrieve(query)
  → hierarchicalStrategy.retrieve()   // 优先层次检索
    → null? flatStrategy.retrieve()   // 失败则平铺兜底
```

### 4.4 数据访问层独立

**新增文件**：`RagUnitQueryRepository.java`

从 `RagRetrievalService` 中抽取 5 个直接 MyBatis 查询方法：

| 方法 | 用途 |
|------|------|
| `selectByIds()` | 批量按 ID 查询 |
| `selectNeighborLeaves()` | 邻居叶子扩展（上下文窗口） |
| `selectChildrenByParentIds()` | 按父节点查询子节点 |
| `searchLeafUnitsByKeyword()` | 关键词模糊搜索 |
| `selectParentSections()` | 父节点批量查询 |

### 4.5 Redis 连接池统一

**文件**：`VectorStoreConfig.java`

| Before | After |
|--------|-------|
| 每个 VectorStore 各自 `new JedisPooled()`（2 个独立连接池） | 共享 1 个 `@Bean JedisPooled` |
| 总连接池数：3（JedisConnectionFactory + 2 × JedisPooled） | 总连接池数：2（JedisConnectionFactory + 1 × JedisPooled） |

### 4.6 代码去重

| 重复代码 | 提取位置 | 消除处数 |
|---------|---------|---------|
| `isValidSha256()` | `HashUtils.isValidSha256()` | 4 处（ChunkUpload、RagDocument、DocumentDelete、asyncBatch） |
| `readStringWithFallback()` | `CharsetUtils.readStringWithFallback()` | 2 处（TextProcessor、TabularRowChunker） |

---

## 五、开发体验

### 5.1 Swagger/OpenAPI 文档

**依赖**：`springdoc-openapi-starter-webmvc-ui:2.6.0`

| 访问地址 | 用途 |
|---------|------|
| `http://localhost:8080/swagger-ui.html` | Swagger UI 交互式文档 |
| `http://localhost:8080/v3/api-docs` | OpenAPI JSON 描述 |

**Controller @Tag 分组**：

| Tag | Controller |
|-----|-----------|
| 认证管理 | AuthController |
| AI 对话 | AiController |
| 文档管理 | RagDocumentController |
| 文件上传 | UploadController |
| 分片上传 | ChunkUploadController |

Swagger 路径已加入 SaToken 排除列表，无需登录即可访问。

### 5.2 日志规范

**新增文件**：`logback-spring.xml`

| Profile | 级别 | 输出 |
|---------|------|------|
| dev | DEBUG（项目包）/ INFO（框架） | 控制台 + 文件 |
| prod | INFO（项目包）/ WARN（框架） | 文件 + ERROR 单独文件 |

文件轮转：单文件 50MB，保留 30 天，总上限 1GB。

### 5.3 Aiconfig.java BOM 修复

移除文件开头的 UTF-8 BOM 字符（`﻿`），修复编译警告。

---

## 六、测试覆盖

| 测试文件 | 用例数 | 覆盖范围 |
|---------|--------|---------|
| `AuthAccountServiceTest` | 4 | 注册、认证、改密、重置密码（含管理员角色 mock） |
| `VectorStoreWriteServiceTest` | **3（新增）** | 批量重试→单条降级、leaf/summary 路由分流、双索引删除 |
| `RagRetrievalServiceTest` | 4 | 多路召回合并、去重、邻居扩展、Redis Tag 转义 |
| `RagRetrievalServiceRecallFallbackTest` | 2 | 关键词回退、主查询兜底 |
| `RagUnitServiceVectorWriteTest` | 4 | 向量写入委托、删除委托、批量插入、flush 策略 |
| `VectorStoreConfigTest` | 1 | MetadataField 注册验证 |
| `AiControllerTest` | 3 | 多轮对话、会话删除委托、引文事件 |
| `AiServicePromptTest` | 1 | System Prompt 知识库约束 |
| 其他（HashUtils、FileNameSanitizer 等） | 64 | 已有测试，全部通过 |
| **总计** | **86** | **全部通过** |

> 注：另有 3 个 master 分支上原有的失败测试（TextProcessorTest Mockito 严格性、EmailServiceTest 环境依赖、DemoApplicationTests 上下文加载），不受本次改动影响。

---

## 七、提交历史

| # | Commit | 描述 |
|---|--------|------|
| 1 | `76b81f3` | `fix(security,quality): 密码验证增强、管理员权限校验、速率限制、连接泄漏修复、代码去重` |
| 2 | `9b680a0` | `Merge branch 'fix/security-and-code-quality'` |
| 3 | `442658c` | `refactor(architecture): 拆分 God Class、提取数据访问层、引入 Swagger、XSS 防护` |
| 4 | `61acbe8` | `refactor(retrieval): 策略模式拆分检索服务、提取向量写入层、统一 Redis 连接池` |

---

## 八、待办事项

| 优先级 | 项目 | 说明 |
|--------|------|------|
| 🔒 HIGH | 硬编码密码 | `application.yaml` 中 MySQL/Redis/MinIO/RabbitMQ 密码明文且已进入 Git 历史，需轮换密码并改用环境变量 |
| 🟡 LOW | 常量魔法值提取 | 散落各处的数值/字符串常量提取为命名常量或 `@ConfigurationProperties`，纯美化项 |

---

## 九、架构对比

### Before

```
RagRetrievalService     792 行  ← 层次检索+平铺+多路召回+rerank+数据查询
RagUnitService          630 行  ← 16 个依赖：7处理器+2向量库+7其他
AiService               303 行  ← 聊天+会话管理+画像提炼+引文
无 AsyncConfig
无 Swagger
无 logback 配置
3 个独立 Redis 连接池
```

### After

```
RagRetrievalService             290 行  ← 纯编排：策略选择+多路召回
  retrieval/HierarchicalRetrievalStrategy  170 行
  retrieval/FlatRetrievalStrategy           90 行
  retrieval/KnowledgeTextBuilder            90 行
  retrieval/RerankHelper                    50 行
  retrieval/UserFilterBuilder               30 行

RagUnitService                  400 行  ← 9 个依赖
  VectorStoreWriteService                 130 行
  processor/MediaProcessorRegistry         35 行

AiService                       218 行  ← 纯聊天编排
  ChatSessionService                      130 行

repository/RagUnitQueryRepository  90 行  ← 数据访问层独立
Config/AsyncConfig                  35 行  ← 统一异步配置+优雅停机
Config/OpenApiConfig                20 行  ← Swagger 配置
logback-spring.xml                  75 行  ← 分级日志
2 个 Redis 连接池（共享 JedisPooled）
```
