# 项目改进分析报告

> 生成时间：2026-06-06  
> 范围：`D:\rag\demo` 当前项目，只读分析结论整理  
> 目的：记录当前发现的问题、推荐做法、下一步执行顺序  
> 状态：本文档仅做分析与规划，不包含代码修改

---

## 1. 项目概览

当前项目是一个基于 **Spring Boot + Vue 3 + RAG** 的文档知识库问答系统。

主要链路包括：

1. 用户注册、登录、权限控制；
2. 文档上传与分片上传；
3. MinIO 存储原始文件；
4. RabbitMQ 异步触发文件处理；
5. 文档解析、切片、摘要生成；
6. MySQL 保存文档和 RAG 单元数据；
7. Redis VectorStore 保存 leaf / summary 向量索引；
8. 问答时进行向量召回、层级检索、rerank；
9. 通过 SSE 流式返回答案和引用来源；
10. 前端提供登录、文档管理、聊天问答、PDF 预览等能力。

整体功能已经比较完整，但当前存在一些上线前必须处理的问题，尤其是 **权限安全、密钥治理、文件上传安全、RAG 数据一致性、异步任务可靠性、测试覆盖和工程化**。

---

## 2. 总体优先级结论

如果只选最需要优先处理的事项，建议按下面顺序：

1. **修复公开注册可指定管理员角色的问题**；
2. **轮换并移除本地配置中的真实密钥**；
3. **修复分片上传路径穿越风险**；
4. **生产环境移除通配 CORS**；
5. **删除文档时同时删除 leaf 和 summary 两个向量索引**；
6. **修复 RabbitMQ ack / DLQ / 重试策略**；
7. **上传后由服务端重新计算并校验文件 hash**；
8. **MinIO 后台处理改用 object key，不依赖长期预签名 URL**；
9. **Controller 和 DTO 补充 Bean Validation**；
10. **修复 `RagRetrievalService` 检索阈值参数不生效的问题**。

其中前 4 项偏安全，建议作为第一批处理；第 5-8 项偏 RAG 数据正确性和生产可靠性，建议作为第二批处理。

---

## 3. P0 问题：必须优先处理

### 3.1 匿名注册可通过 `roleCode` 自选角色，存在管理员提权风险

**问题描述：**

公开注册接口允许前端传入角色字段，前端注册页也存在角色选择。如果后端没有强制覆盖为普通用户，攻击者可能注册时提交 `roleCode=admin`，直接获得管理员权限。

**证据：**

- `src/main/java/com/example/demo/Controller/AuthController.java:43-46`
- `src/main/java/com/example/demo/service/AuthApplicationService.java:37-43`
- `src/main/java/com/example/demo/service/AuthAccountService.java:45-48`
- `src/main/java/com/example/demo/service/AuthAccountService.java:175-180`
- `src/main/java/com/example/demo/service/AuthSeedService.java:27-35`
- `frontend/src/views/Register.vue:84-93`
- `frontend/src/views/Register.vue:153`

**影响：**

- 普通用户可能自助注册成管理员；
- 管理员权限可能包含用户管理、密码重置等高危操作；
- 属于上线阻断级安全问题。

**推荐做法：**

1. 公开注册接口禁止客户端指定角色；
2. 后端强制新注册用户绑定默认 `user` 角色；
3. 管理员角色只能由已授权管理员在后台分配；
4. 前端注册页移除角色选择；
5. 检查现有用户数据中是否存在异常管理员账号。

**下一步：**

- 优先修改后端注册逻辑；
- 再调整前端注册表单；
- 增加测试：匿名注册即使传入 `admin`，最终也只能是普通用户。

---

### 3.2 本地开发配置存在真实凭据，需要按泄露处理

**问题描述：**

`application-dev.yaml` 中存在多类真实服务凭据，包括模型服务、数据库、Redis、RabbitMQ、MinIO、阿里云、MinerU、百度 MCP 等。即使文件被 `.gitignore` 忽略，只要本地工作区、日志、IDE 缓存、历史提交或备份里出现过，都应该按泄露处理。

**证据：**

- `src/main/resources/application-dev.yaml:7`
- `src/main/resources/application-dev.yaml:9-11`
- `src/main/resources/application-dev.yaml:13-15`
- `src/main/resources/application-dev.yaml:17-20`
- `src/main/resources/application-dev.yaml:22-24`
- `src/main/resources/application-dev.yaml:27-31`
- `src/main/resources/application-dev.yaml:34`
- `.gitignore:4-5`

**影响：**

- 数据库、对象存储、消息队列可能被接管；
- AI/API 服务可能被滥用并产生费用；
- 用户文档、向量数据、会话数据可能泄露；
- 如果历史提交中出现过，需要按真实泄露事件处理。

**推荐做法：**

1. 立即轮换所有出现过的密钥和密码；
2. 本地真实配置不要放在项目目录内，或确保完全不纳入版本管理；
3. 仓库中只保留 `application-dev.example.yaml`；
4. 使用环境变量、密钥管理服务或部署平台 Secret 注入真实值；
5. CI 增加 secret scanning；
6. 检查 git 历史、日志、IDE 缓存、构建产物中是否出现过这些值。

**下一步：**

- 先轮换密钥；
- 再清理配置文件；
- 最后补充密钥扫描和启动时配置校验。

---

### 3.3 分片合并存在路径穿越风险

**问题描述：**

分片上传合并时，`filename` 来自请求参数，并直接参与临时文件路径拼接。如果文件名中包含 `../`、路径分隔符、绝对路径、控制字符或 Windows 特殊设备名，可能写出预期临时目录。

**证据：**

- `src/main/java/com/example/demo/Controller/ChunkUploadController.java:77-81`
- `src/main/java/com/example/demo/service/ChunkUploadApplicationService.java:83-89`
- `src/main/java/com/example/demo/service/ChunkUploadService.java:88-90`

**影响：**

- 可能覆盖服务进程可写范围内的文件；
- 可能污染临时目录和后续 MinIO 对象名；
- 可能与文档解析链路组合形成更严重风险。

**推荐做法：**

1. 服务端生成安全文件名和 object key；
2. 原始文件名只作为展示字段，不参与路径构造；
3. 拒绝 `..`、`/`、`\`、控制字符、绝对路径、Windows 保留设备名；
4. `resolve` 后调用 `normalize()`；
5. 校验最终路径仍位于预期临时目录内。

**下一步：**

- 优先在分片合并处加路径规范化和校验；
- 增加路径穿越测试用例；
- 检查普通上传和 MinIO object key 是否也存在类似问题。

---

### 3.4 敏感接口开放通配 CORS

**问题描述：**

上传、分片上传、文档管理等接口使用 `@CrossOrigin(origins = "*")`，允许任意来源网页跨域访问。

**证据：**

- `src/main/java/com/example/demo/Controller/UploadController.java:24-27`
- `src/main/java/com/example/demo/Controller/ChunkUploadController.java:24-27`
- `src/main/java/com/example/demo/Controller/RagDocumentController.java:25-28`

**影响：**

- 任意网站都可以尝试调用这些接口；
- 如果 token 存储、浏览器策略、前端 XSS 出现问题，风险会被放大；
- 生产环境不应对敏感接口使用通配跨域。

**推荐做法：**

1. 移除 Controller 上分散的 `@CrossOrigin(origins = "*")`；
2. 建立统一 CORS 配置；
3. 按环境配置前端域名白名单；
4. 限制允许的方法、请求头、暴露头；
5. 上传、删除、密码重置等高风险接口增加限流和审计日志。

**下一步：**

- 定义开发环境和生产环境允许的前端域名；
- 改为统一 CORS 配置；
- 增加安全测试确认非白名单域名不能访问。

---

## 4. P1 问题：影响稳定性和可维护性

### 4.1 删除文档可能只删除 leaf 向量，不删除 summary 向量

**问题描述：**

索引写入时，系统将 leaf 节点写入 `leafVectorStore`，summary 节点写入 `summaryVectorStore`。但删除链路中只注入了一个 `VectorStore`，而 `leafVectorStore` 是 `@Primary`，因此删除时可能只删除 leaf 向量，summary 向量残留。

**证据：**

- `src/main/java/com/example/demo/service/RagUnitService.java:390-416`
- `src/main/java/com/example/demo/service/FileDeleteConsumer.java:22-28`
- `src/main/java/com/example/demo/service/FileDeleteConsumer.java:104-109`
- `src/main/java/com/example/demo/Config/VectorStoreConfig.java:22-37`
- `src/main/java/com/example/demo/Config/VectorStoreConfig.java:39-53`

**影响：**

- 已删除文档的摘要可能仍被检索召回；
- 造成脏数据、错误答案或数据泄露；
- 对 RAG 正确性影响很大。

**推荐做法：**

1. 删除消费者显式注入 `leafVectorStore` 和 `summaryVectorStore`；
2. 删除时两个索引都执行删除；
3. 删除任务记录两个索引的删除结果；
4. 提供补偿任务用于清理残留向量。

**下一步：**

- 修复删除逻辑；
- 增加集成测试：上传、索引、删除后 leaf 和 summary 均不可召回。

---

### 4.2 RabbitMQ 失败消息被 ack，DLQ 和重试机制不完整

**问题描述：**

项目声明了 DLQ，但文件处理和删除消费者在异常后仍执行 ack。部分主队列也可能没有绑定死信参数。这样 RabbitMQ 认为消息已消费成功，无法进入死信队列，也不利于集中重试、告警和人工重放。

**证据：**

- `src/main/java/com/example/demo/Config/RabbitMQConfig.java:28-40`
- `src/main/java/com/example/demo/Config/RabbitMQConfig.java:105-107`
- `src/main/java/com/example/demo/Config/RabbitMQConfig.java:113-115`
- `src/main/java/com/example/demo/service/FileProcessConsumer.java:86-94`
- `src/main/java/com/example/demo/service/FileDeleteConsumer.java:97-101`
- `src/main/java/com/example/demo/service/FileProcessConsumer.java:158-166`
- `src/main/java/com/example/demo/service/FileDeleteConsumer.java:148-156`

**影响：**

- 文件处理失败后只能落业务失败状态，队列层面不可重放；
- 队列堆积、第三方服务抖动、临时网络错误时恢复能力弱；
- 运维排障困难。

**推荐做法：**

1. 主队列配置 `x-dead-letter-exchange` 和 dead-letter routing key；
2. 区分可重试异常和不可重试异常；
3. 可重试异常走 `basicNack(requeue=false)` 或延迟重试；
4. 不可重试异常 ack 并落业务失败状态；
5. 增加最大重试次数、DLQ 告警和人工重放能力；
6. 保证消费者幂等，避免重复写库和重复写向量。

**下一步：**

- 先明确失败分类；
- 再补齐队列参数和消费者处理策略；
- 最后加重试/DLQ 集成测试。

---

### 4.3 数据库写入和向量库写入不具备跨存储原子性

**问题描述：**

MySQL 事务中包含向量库写入调用，但 Redis VectorStore 是外部副作用，不能随 MySQL 事务回滚。这样可能出现 DB 和向量索引不一致。

**证据：**

- `src/main/java/com/example/demo/service/FileProcessConsumer.java:104-140`
- `src/main/java/com/example/demo/service/RagUnitService.java:390-415`
- `src/main/java/com/example/demo/service/RagUnitService.java:418-488`
- `src/main/java/com/example/demo/service/RagUnitService.java:365-388`

**影响：**

- MySQL 成功但向量写失败；
- 向量写成功但 MySQL 回滚；
- 出现孤儿向量、重复向量或检索命中失败文档；
- 影响 RAG 答案可信度。

**推荐做法：**

1. 使用 Outbox 或索引任务表；
2. MySQL 先提交文档、切片和索引任务状态；
3. 向量写入作为独立、幂等、可重试步骤；
4. 记录索引状态，例如 `PENDING`、`INDEXING`、`SUCCESS`、`FAILED`；
5. 提供按 `sourceId` 重建索引的后台任务；
6. 删除也要支持补偿和幂等。

**下一步：**

- 先增加索引状态字段或任务表设计；
- 再拆分向量写入流程；
- 最后补偿重建任务和测试。

---

### 4.4 上传链路信任客户端 `fileHash`

**问题描述：**

当前上传链路校验了客户端传入的 hash 格式，但没有看到服务端重新计算上传内容 SHA-256 并比对。

**证据：**

- `src/main/java/com/example/demo/service/RagUnitService.java:187-193`
- `src/main/java/com/example/demo/service/RagUnitService.java:225-230`

**影响：**

- 攻击者可以伪造 hash；
- 可能影响文件去重、处理中状态判断、删除和检索隔离；
- 数据库记录、MinIO 对象和向量索引之间的文件身份可能不一致。

**推荐做法：**

1. 普通上传时服务端流式计算 SHA-256；
2. 分片上传合并后重新计算整文件 SHA-256；
3. 客户端 hash 只能作为提前校验提示，不能作为可信依据；
4. 所有去重、状态机和 sourceId 以服务端计算结果为准。

**下一步：**

- 补服务端 hash 计算；
- 增加 hash 不一致拒绝测试；
- 调整去重逻辑只信任服务端 hash。

---

### 4.5 MinIO 后台处理依赖长期预签名 URL

**问题描述：**

文件处理消费者通过 `minioUrl` 下载文件，而预签名 URL 有有效期。队列堆积、重试或人工恢复超过有效期后，任务可能无法处理。同时，长期存储可访问 URL 也会扩大文件访问面。

**证据：**

- `src/main/java/com/example/demo/service/UploadService.java:65-73`
- `src/main/java/com/example/demo/service/FileProcessConsumer.java:46`
- `src/main/java/com/example/demo/service/FileProcessConsumer.java:96-101`

**影响：**

- 异步任务可能因为 URL 过期失败；
- 数据库中长期保存可访问 URL，增加泄露风险；
- 日志或第三方处理链路可能暴露文件访问地址。

**推荐做法：**

1. 数据库保存 MinIO object key / `minioPath`；
2. 后台消费者使用 MinIO SDK `getObject` 读取文件；
3. 预签名 URL 仅在用户下载或预览时短期生成；
4. 日志中不要输出完整 URL。

**下一步：**

- 梳理 `minioUrl` 和 `minioPath` 使用点；
- 后台处理改为 object key 读取；
- 前端预览接口按需生成短期 URL。

---

### 4.6 Controller 请求体缺少 Bean Validation

**问题描述：**

多个 Controller 直接接收 `@RequestBody`，DTO 字段缺少 `@NotBlank`、`@Size`、`@Email` 等约束。

**证据：**

- `src/main/java/com/example/demo/Controller/AuthController.java:44`
- `src/main/java/com/example/demo/Controller/AuthController.java:57`
- `src/main/java/com/example/demo/Controller/AuthController.java:95`
- `src/main/java/com/example/demo/Controller/AuthController.java:109`
- `src/main/java/com/example/demo/Controller/AuthController.java:122`
- `src/main/java/com/example/demo/Controller/AuthController.java:135`
- `src/main/java/com/example/demo/Controller/AiController.java:43`
- `src/main/java/com/example/demo/Controller/AiController.java:54`
- `src/main/java/com/example/demo/Controller/AiController.java:65`
- `src/main/java/com/example/demo/Controller/AiController.java:87`
- `src/main/java/com/example/demo/Controller/AiController.java:98`
- `src/main/java/com/example/demo/model/dto/MultiTurnChatRequest.java:13-16`

**影响：**

- 空值和非法输入进入业务层；
- 超长 prompt 或异常 session id 可能导致资源滥用；
- 错误响应不一致；
- 安全边界不清晰。

**推荐做法：**

1. Controller 参数增加 `@Valid`；
2. DTO 字段增加 `@NotBlank`、`@Size`、`@Email`、格式约束；
3. 密码字段增加复杂度规则；
4. prompt / message 增加最大长度；
5. `GlobalExceptionHandler` 统一处理校验异常。

**下一步：**

- 先覆盖认证、密码重置、聊天入口；
- 再覆盖上传和文档管理；
- 增加参数校验测试。

---

## 5. P2 问题：工程质量和长期维护

### 5.1 `RagUnitService` 职责过重

**问题描述：**

`RagUnitService` 同时负责上传编排、文件检查、MIME 识别、processor 路由、文档查询、删除、MySQL 批量写入、向量写入等职责。

**证据：**

- `src/main/java/com/example/demo/service/RagUnitService.java:43-101`
- `src/main/java/com/example/demo/service/RagUnitService.java:130-180`
- `src/main/java/com/example/demo/service/RagUnitService.java:188-255`
- `src/main/java/com/example/demo/service/RagUnitService.java:257-283`
- `src/main/java/com/example/demo/service/RagUnitService.java:390-521`

**影响：**

- 服务类越来越大；
- 修改一个文件处理逻辑容易影响上传、删除或索引；
- 新增文件类型或索引策略成本高；
- 单元测试困难。

**推荐做法：**

按领域拆分：

- `document`：文档生命周期和查询；
- `ingestion`：上传、分片、MinIO；
- `parsing`：文件解析和 processor 路由；
- `indexing`：层级索引、摘要、向量写入；
- `retrieval`：检索；
- `chat`：会话和问答；
- `auth`：认证和权限。

**下一步：**

- 先画出当前调用关系；
- 选择风险最低的职责先抽离，例如 processor 路由；
- 重构前补关键回归测试。

---

### 5.2 `RagRetrievalService` 过大，检索链路耦合重

**问题描述：**

`RagRetrievalService` 包含查询规划、向量召回、层级下钻、关键词兜底、rerank、邻居扩展、引用构造等逻辑。

**证据：**

- `src/main/java/com/example/demo/service/RagRetrievalService.java:37-78`
- `src/main/java/com/example/demo/service/RagRetrievalService.java:109-189`
- `src/main/java/com/example/demo/service/RagRetrievalService.java:228-389`
- `src/main/java/com/example/demo/service/RagRetrievalService.java:413-570`
- `src/main/java/com/example/demo/service/RagRetrievalService.java:723-789`

**影响：**

- 难以增加 BM25、混合检索、query routing、A/B 实验；
- rerank、上下文扩展、引用构造互相耦合；
- 性能优化和质量评估不容易定位。

**推荐做法：**

拆分为：

- `QueryPlanner`
- `VectorRetriever`
- `KeywordRetriever`
- `RerankService`
- `HierarchyNavigator`
- `ContextExpander`
- `CitationAssembler`
- `RetrievalMetricsRecorder`

**下一步：**

- 先补现有检索行为测试；
- 再从无状态工具方法开始抽离；
- 最后调整检索流程编排。

---

### 5.3 检索阈值参数不生效

**问题描述：**

`retrieve(String query, int topK, double hitThreshold)` 方法接收了 `hitThreshold` 参数，但内部调用时使用的是成员变量 `hitScoreThreshold`，导致调用方传入的阈值被忽略。

**证据：**

- `src/main/java/com/example/demo/service/RagRetrievalService.java:87-89`

**影响：**

- 调用方以为可以动态调整命中阈值，实际不生效；
- 可能导致命中判断过松或过严；
- 影响 RAG 召回质量和无答案判断。

**推荐做法：**

1. 改为传递方法参数 `hitThreshold`；
2. 增加单元测试覆盖不同阈值下的命中行为；
3. 检查其他重载方法是否存在类似参数被忽略问题。

**下一步：**

- 这是一个小而明确的 bug，适合在安全问题后作为 quick win 修复。

---

### 5.4 前端 token 存储在 `localStorage`

**问题描述：**

前端认证 token 持久化在 `localStorage`，请求时从本地读取并放入 header。

**证据：**

- `frontend/src/store/auth.js:6-7`
- `frontend/src/store/auth.js:26-27`
- `frontend/src/api/index.js:9-13`

**影响：**

- 一旦发生 XSS，攻击者可以直接读取 token；
- token 长期落盘，风险窗口较长。

**推荐做法：**

1. 优先使用 `HttpOnly + Secure + SameSite` Cookie；
2. 如果必须前端持有 token，尽量使用内存存储；
3. 缩短 token 有效期；
4. 增加 CSP；
5. 避免引入不可信第三方脚本。

**下一步：**

- 先确认后端 Sa-Token 当前 token 传递方式；
- 再决定是否迁移到 Cookie 模式。

---

### 5.5 前端 PDF iframe 预览缺少来源校验和 sandbox

**问题描述：**

前端直接使用后端返回的文档 URL 进行 iframe 预览，缺少可信来源校验和 iframe sandbox 限制。

**证据：**

- `frontend/src/views/Chat.vue:403-408`
- `frontend/src/views/Chat.vue:231-234`
- `frontend/src/views/Documents.vue:394-397`
- `frontend/src/views/Documents.vue:356-359`

**影响：**

- 如果 URL 被污染，应用内会嵌入不可信页面；
- 可能带来钓鱼、点击劫持、隐私泄露风险。

**推荐做法：**

1. 只允许可信对象存储域名；
2. 校验 URL 协议必须是 HTTPS；
3. iframe 增加 `sandbox`；
4. 增加 `referrerpolicy`；
5. 必要时由后端代理预览文件。

**下一步：**

- 先加 URL 白名单判断；
- 再给 iframe 增加 sandbox 配置。

---

### 5.6 前端核心页面过大，职责集中

**问题描述：**

`Chat.vue` 和 `Documents.vue` 承担了大量职责，包括 UI、API 请求、轮询、上传、语音录制、Markdown 渲染、PDF 预览等。

**证据：**

- `frontend/src/views/Chat.vue:241-850`
- `frontend/src/views/Documents.vue:366-817`

**影响：**

- 后续改动容易互相影响；
- 测试困难；
- 组件复用性差；
- 页面状态复杂度越来越高。

**推荐做法：**

拆分为：

- 会话列表组件；
- 消息流组件；
- 知识来源面板；
- 语音录制 composable；
- 上传队列组件；
- 文档表格组件；
- PDF 预览组件；
- API 状态管理 composable。

**下一步：**

- 重构前先补 E2E 或组件测试；
- 优先抽离上传队列和 PDF 预览这种边界清晰的模块。

---

### 5.7 测试、lint、CI 和部署工程化不足

**问题描述：**

项目功能复杂，但自动化质量门禁不足。前端缺少测试、lint、format 脚本；后端关键业务链路缺少系统性集成测试；项目未看到完整 Docker、CI、数据库迁移工具。

**证据：**

- `frontend/package.json:6-10`
- `.gitignore:1-5`
- `frontend/README.md:1-5`
- `pom.xml:198-202`
- 未发现 Dockerfile / docker-compose / GitHub Actions / Flyway / Liquibase。

**影响：**

- 登录、上传、索引、删除、问答链路容易回归；
- 本地和生产环境不一致；
- 数据库 schema 演进难以管理；
- 上线风险较高。

**推荐做法：**

前端：

- 增加 Vitest；
- 增加 Vue Test Utils；
- 增加 ESLint / Prettier；
- 增加 Playwright E2E；
- 覆盖登录、上传、聊天、文档删除流程。

后端：

- 增加 service 单元测试；
- 增加 controller 集成测试；
- 使用 Testcontainers 覆盖 MySQL、Redis、RabbitMQ、MinIO；
- 增加 RAG 检索行为测试；
- 增加删除双向量索引的回归测试。

工程化：

- 增加 Dockerfile；
- 增加 docker-compose；
- 增加 CI；
- 引入 Flyway 或 Liquibase；
- 补充 `.env.example` 和启动文档。

**下一步：**

- 先补关键安全和 RAG 回归测试；
- 再补 CI；
- 最后补本地一键启动和数据库迁移。

---

## 6. 推荐实施路线

### 阶段一：安全止血

建议优先处理时间：1-3 天。

目标：解决上线阻断级安全问题。

任务：

1. 禁止注册时传入 `roleCode`；
2. 移除前端注册角色选择；
3. 轮换并移除所有明文密钥；
4. 修复分片上传路径穿越风险；
5. 收敛生产环境 CORS；
6. 禁止日志和前端暴露密码重置码；
7. 全局异常响应去除内部错误详情；
8. 登录、注册、找回密码增加限流。

验收标准：

- 匿名注册无法创建管理员；
- 仓库和工作区没有真实密钥；
- 路径穿越文件名被拒绝；
- 非白名单域名无法调用敏感接口；
- 500 响应不泄露内部异常详情。

---

### 阶段二：RAG 数据一致性和任务可靠性

建议优先处理时间：3-7 天。

目标：保证上传、索引、删除、检索链路可靠。

任务：

1. 删除文档时同时删除 leaf 和 summary 向量；
2. 修复 RabbitMQ ack / nack / DLQ 策略；
3. 为主队列绑定死信交换机和 routing key；
4. 上传后服务端重新计算 hash；
5. 后台文件处理改用 MinIO object key；
6. 索引写入拆分为可重试任务；
7. 增加索引重建和补偿删除能力。

验收标准：

- 删除文档后 leaf 和 summary 都无法召回；
- 文件处理失败能进入重试或 DLQ；
- hash 不一致上传会被拒绝；
- 预签名 URL 过期不影响后台处理；
- 向量写入失败可以重试或重建。

---

### 阶段三：输入校验和测试补强

建议优先处理时间：1-2 周。

目标：降低回归风险和非法输入风险。

任务：

1. Controller 参数增加 `@Valid`；
2. DTO 增加字段约束；
3. 全局处理校验异常；
4. 增加认证、上传、分片、删除、检索测试；
5. 前端增加基础组件测试和 E2E；
6. CI 中执行后端测试和前端构建。

验收标准：

- 非法请求稳定返回 400；
- 关键业务链路有自动化测试；
- CI 能自动发现构建或测试失败。

---

### 阶段四：模块化重构和生产化

建议优先处理时间：2-4 周。

目标：提升长期维护性、扩展性和部署可靠性。

任务：

1. 拆分 `RagUnitService`；
2. 拆分 `RagRetrievalService`；
3. 建立 `ProcessorRegistry`；
4. 配置项类型化；
5. 引入 Flyway / Liquibase；
6. 增加 Dockerfile 和 docker-compose；
7. 增加 Actuator 最小暴露配置；
8. 增加 metrics、trace、RAG 质量评估。

验收标准：

- 新增文件类型不需要修改核心索引服务；
- 新增检索策略不需要大改问答服务；
- 本地依赖可一键启动；
- 数据库 schema 可版本化迁移；
- 生产指标和日志足够排障。

---

## 7. 建议新增测试清单

### 7.1 安全测试

1. 匿名注册传入 `roleCode=admin`，最终仍为普通用户；
2. 普通用户不能访问管理员接口；
3. 路径穿越文件名被拒绝；
4. 非白名单 Origin 调用上传接口失败；
5. 密码重置码不会出现在响应体；
6. 重置码多次失败后失效或锁定；
7. 500 错误响应不包含内部异常 message。

### 7.2 上传与索引测试

1. 客户端 hash 与服务端 hash 不一致时拒绝上传；
2. 分片上传合并后重新计算 hash；
3. 超大文件、超多分片被拒绝；
4. 上传成功后生成 RAG 单元；
5. 向量写入失败后任务可重试；
6. 删除文档后 MySQL、leaf vector、summary vector 均清理。

### 7.3 检索测试

1. 不同 `hitThreshold` 参数能影响命中判断；
2. summary 检索不足时能回退 leaf 检索；
3. rerank 失败时有稳定降级；
4. 删除文档不会被召回；
5. 引用来源和答案上下文一致。

### 7.4 前端测试

1. 注册页不展示角色选择；
2. 找回密码页不展示重置码；
3. 上传队列正确展示失败原因；
4. 超大文件前端直接拦截；
5. 聊天流式响应正常展示；
6. PDF 预览只允许可信 URL。

---

## 8. 风险矩阵

| 优先级 | 问题 | 风险类型 | 建议处理时机 |
|---|---|---|---|
| P0 | 注册可指定 admin 角色 | 权限提升 | 立即 |
| P0 | 明文密钥 | 凭据泄露 | 立即 |
| P0 | 分片路径穿越 | 文件系统安全 | 立即 |
| P0 | 通配 CORS | 跨站风险 | 立即 |
| P1 | 删除只删 leaf 向量 | 数据泄露 / 脏召回 | 第一批 |
| P1 | RabbitMQ 失败 ack | 可靠性 | 第一批 |
| P1 | DB 与向量库不一致 | 数据一致性 | 第一批 |
| P1 | 信任客户端 hash | 数据完整性 | 第一批 |
| P1 | 预签名 URL 用于后台任务 | 可用性 / 数据暴露 | 第一批 |
| P1 | 缺少 Bean Validation | 输入边界 | 第二批 |
| P2 | 服务职责过重 | 可维护性 | 后续重构 |
| P2 | 检索阈值参数不生效 | 功能正确性 | Quick win |
| P2 | 前端 token localStorage | XSS 后果扩大 | 后续安全增强 |
| P2 | 测试和 CI 不足 | 回归风险 | 持续补强 |

---

## 9. 建议下一步执行计划

### 下一步 1：确认是否开始第一阶段修复

建议先确认以下范围：

- 是否立即修复注册角色提权；
- 是否立即轮换凭据；
- 是否先做分片路径安全；
- 是否同步改 CORS。

### 下一步 2：为第一阶段建立任务清单

建议拆成 4 个小任务：

1. `fix-auth-registration-role`：修复注册角色提权；
2. `fix-secret-management`：密钥清理和配置模板化；
3. `fix-chunk-upload-path`：分片上传路径安全；
4. `fix-cors-policy`：统一 CORS 白名单。

### 下一步 3：每个任务先补测试再改代码

优先测试：

- 注册传 `admin` 不生效；
- 路径穿越文件名被拒绝；
- 非白名单 Origin 被拒绝；
- 配置缺失时启动失败或给出明确错误。

### 下一步 4：完成后做一次安全复审

修完第一阶段后，建议重新跑一次安全审查，确认没有残留：

- admin 提权；
- 密钥泄露；
- 路径穿越；
- CORS 过宽；
- 错误信息泄露。

---

## 10. 当前建议结论

当前项目已经具备完整 RAG 产品雏形，但如果准备进一步上线或给更多用户使用，建议先不要继续堆功能，而是优先处理：

1. **权限安全**；
2. **密钥治理**；
3. **上传安全**；
4. **删除和索引一致性**；
5. **异步任务可靠性**；
6. **关键测试覆盖**。

这些问题处理完后，再做模块化重构和体验优化，会更稳。