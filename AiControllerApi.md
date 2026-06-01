# RAG 知识库与问答系统接口文档

文档包含了 AI 问答对话、文档库管理、大文件分片上传、常规文件上传的相关接口定义。

---

## 1. AI 会话相关接口 (AiController)

此模块维护与 AI 大模型的交互，支持基础问答、基于 RAG 的多轮会话以及用户画像与长期记忆处理。

### 1.1 简易聊天接口
* **接口路径**: `GET /ai/chatmemory/chat`
* **功能描述**: 支持向量粗召回与 Rerank 精排的问答接口。通过工具获取时间并拉取知识库资料辅助大模型回答。
* **Query 参数**:
  * `msg` (String) - 用户输入的对话内容
  * `userId` (String) - 用户唯一标识 (作为会话ID)
* **响应格式**: 纯文本流 (`text/plain;charset=UTF-8`)，直接返回大模型回答的内容字符串。

### 1.2 获取用户会话列表
* **接口路径**: `POST /ai/session/list`
* **功能描述**: 获取指定用户下的所有活跃会话记录集合。
* **请求格式 (JSON Body)**:
  ```json
  {
    "userId": "string"
  }
  ```
* **响应格式**:
  ```json
  {
    "code": 200,
    "message": "success",
    "data": {
      "userId": "string",
      "sessions": ["session-id-1", "session-id-2"],
      "total": 2,
      "timestamp": 1700000000000
    }
  }
  ```

### 1.3 删除会话
* **接口路径**: `POST /ai/session/delete`
* **功能描述**: 删除指定的独立会话记录。系统在删除前会异步提取该会话的历史对话以充实用户画像（长期记忆），然后再从缓存中清理会话数据。
* **请求格式 (JSON Body)**:
  ```json
  {
    "sessionId": "string",
    "userId": "string"
  }
  ```
* **响应格式**:
  ```json
  {
    "code": 200,
    "message": "success",
    "data": {
       "sessionId": "string",
       "timestamp": 1700000000000,
       "message": "会话删除成功，画像提炼已在后台进行"
    }
  }
  ```

### 1.4 创建新会话
* **接口路径**: `POST /ai/session/create`
* **功能描述**: 为指定用户初始化一轮新的问答会话通道，并分配唯一的 Session ID。
* **请求格式 (JSON Body)**:
  ```json
  {
    "userId": "string"
  }
  ```
* **响应格式**:
  ```json
  {
    "code": 200,
    "message": "success",
    "data": {
       "sessionId": "string",
       "timestamp": 1700000000000,
       "message": "会话创建成功"
    }
  }
  ```

### 1.5 多轮对话
* **接口路径**: `POST /ai/multi-turn/chat`
* **功能描述**: 支持长程多轮对话的核心接口。整合了用户输入的查询改写功能、RAG 全量检索增强、以及用户长期记忆（画像）自动注入系统级 Prompt。
* **请求格式 (JSON Body)**:
  ```json
  {
      "sessionId": "string",
      "message": "string",
      "turnCount": 0
  }
  ```
* **响应格式**:
  ```json
  {
    "code": 200,
    "message": "success",
    "data": {
       "sessionId": "string",
       "turnCount": 1,
       "reply": "大模型的长文本回复内容",
       "hitKnowledge": true,
       "referenceCount": 3,
       "timestamp": 1700000000000
    }
  }
  ```

---

## 2. RAG 文档管理接口 (RagDocumentController)

该模块处理针对上传文档查询和删除相关的核心功能。

### 2.1 分页查询文档列表
* **接口路径**: `GET /api/documents`
* **功能描述**: 支持关键字搜索、类型过滤以及分页的文档信息查询
* **Query 参数**:
  * `page` (Integer) - 页码，默认 `1`
  * `pageSize` (Integer) - 每页大小，默认 `10`
  * `sourceType` (String，可选) - 文件类型过滤 (可选值：`TEXT`, `IMAGE`, `VIDEO`)
  * `keyword` (String，可选) - 文件名关键词搜索
  * `sortBy` (String，可选) - 排序字段，默认 `createdAt`
  * `sortOrder` (String，可选) - 排序方向，默认 `DESC`
* **响应格式**:
  ```json
  {
    "code": 200,
    "message": "查询成功",
    "data": {
      "items": [
        // RagDocumentInfo 对象列表
      ],
      "total": 100,
      "page": 1,
      "pageSize": 10,
      "totalPages": 10
    }
  }
  ```

### 2.2 删除指定文档（Hash模式）
* **接口路径**: `DELETE /api/documents/{fileHash}`
* **功能描述**: 异步删除指定文件及其关联向量，基于SHA-256文件哈希防止误删。
* **Path 参数**:
  * `fileHash` (String) - 文件的 SHA-256 唯一哈希值
* **返回格式**:
  ```json
  {
    "code": 200,
    "message": "删除任务已提交",
    "data": {
       "taskId": "task-uuid",
       "fileHash": "...",
       "message": "删除任务已提交，正在后台处理中..."
    }
  }
  ```

### 2.3 查询删除任务状态
* **接口路径**: `GET /api/documents/delete-status/{taskId}`
* **功能描述**: 获取后台删除任务的当前执行状态
* **Path 参数**:
  * `taskId` (String) - 提交删除请求时返回的任务 ID
* **响应格式**: 
  ```json
  {
    "code": 200,
    "message": "查询成功",
    "data": {
       // 删除任务状态详情
    }
  }
  ```

---

## 3. 大文件分片上传接口 (ChunkUploadController)

支持大文件断点续传、秒传特性相关的接口。

### 3.1 检查上传状态 (秒传及断点)
* **接口路径**: `GET /api/upload/chunk/check`
* **功能描述**: 验证文件是否存在以及获取已上传的文件分片记录。
* **Query 参数**:
  * `fileHash` (String) - 文件完整 SHA-256 哈希
  * `filename` (String) - 文件名
  * `fileSize` (Long) - 文件总大小(Bytes)
  * `totalChunks` (Integer) - 拆分的总分片数
* **响应格式**:
  ```json
  {
    "code": 200,
    "message": "可以继续上传", // 或 "文件已存在，秒传成功"
    "data": {
      "uploadedChunks": [0, 1, 2], // 已经上传成功的分片下标
      "progress": 30.5
    }
  } 
  // 秒传的情况下可能直接返回 sourceId 等相关信息
  ```

### 3.2 上传单个分片
* **接口路径**: `POST /api/upload/chunk`
* **功能描述**: 将切分好的单块数据上传到服务端缓存（例如Redis/MinIO暂存）。
* **表单/Body (multipart/form-data)**:
  * `fileHash` (String) - 文件完整 SHA-256 哈希
  * `chunkNumber` (Integer) - 当前片数所在下标 (从 0 开始)
  * `chunk` (File) - 实际的分片二进制文件流数据
* **响应格式**:
  ```json
  {
    "code": 200,
    "message": "分片上传成功",
    "data": {
      "fileHash": "...",
      "chunkNumber": 0,
      "success": true
    }
  }
  ```

### 3.3 合并分片
* **接口路径**: `POST /api/upload/chunk/merge`
* **功能描述**: 将全部分片上传完毕后，请求后端合并原始文件，并转入 RAG 异步处理队列。
* **表单参数 (x-www-form-urlencoded 或 params)**:
  * `fileHash` (String) - 文件的完整 SHA-256
  * `filename` (String) - 最终合并的目标文件名
* **响应格式**:
  ```json
  {
    "code": 200,
    "message": "合并成功",
    "data": {
       "fileHash": "...",
       "filename": "...",
       "sourceId": "...",
       "success": true,
       "message": "文件合并成功，正在后台处理中..."
    }
  }
  ```

---

## 4. 常规文件上传接口 (UploadController)

标准的、支持校验的文件普通上传模块。

### 4.1 检查文件存在性
* **接口路径**: `GET /api/upload/check`
* **功能描述**: 在上传文件前，比对数据库和云存储判定文件是否被上传过。
* **Query 参数**:
  * `fileHash` (String) - 文件的 SHA-256
* **响应格式**: 
  ```json
  {
    "code": 200,
    "message": "文件已存在/文件不存在... ",
    "data": {
       "exists": true,
       "sourceId": "..." 
    }
  }
  ```

### 4.2 单文件直接上传
* **接口路径**: `POST /api/upload`
* **功能描述**: 单体文件异步上传至 MinIO 并执行向量化解析。带 SHA-256 去重机制。
* **表单/Body (multipart/form-data)**:
  * `file` (File) - 需要上传的文件实体
  * `fileHash` (String) - 文件 SHA-256 唯一摘要
* **响应格式**:
  ```json
  {
    "code": 200,
    "message": "上传成功/处理中",
    "data": {
      "filename": "...",
      "success": true,
      "message": "..."
    }
  }
  ```

### 4.3 批量文件直接上传
* **接口路径**: `POST /api/upload/batch`
* **功能描述**: 支持最大一次性携带 20 个文件的多文件批量直传。
* **表单/Body (multipart/form-data)**:
  * `files` (File[]) - 文件列表数组
  * `fileHashes` (String[]) - 按顺序对应的各文件 Hash (与 files 数组一一对应)
* **响应格式**:
  返回结果 `data` 为对应的结果列表：
  ```json
  {
     "code": 200,
     "message": "批量上传完成 (新上传: X, 已存在: Y, 失败: Z)",
     "data": [
       {
           "filename": "xxx.pdf",
           "success": true,
           "message": "..."
       }
     ]
  }
  ```
