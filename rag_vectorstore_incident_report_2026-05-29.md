# RAG 检索故障排查报告

## 背景

现象是：上传文档后，文档在列表中显示已成功，但在聊天里提问文档相关内容时，没有返回知识库命中结果。

本次排查目标：

- 确认问题发生在上传、切片、向量化、检索还是前端展示阶段
- 找到根因
- 给出可执行的修复和清理方案

## 排查过程

### 1. 检查代码链路

先核对了后端主链路：

- `RagUnitService.processAndStoreAsync`
  - 只负责上传文件、写 `document_file` 记录、投递异步任务
- `FileProcessConsumer.processFile`
  - 负责切片、构建层级索引、写 MySQL、写 Redis 向量索引、最后更新状态为 `SUCCESS`
- `RagRetrievalService`
  - 先查摘要索引 `rag-summary-index`
  - 再回退叶子索引 `atguigu-index`
  - 检索时会按 `user_id` 做过滤

初步结论：

- “上传成功”不等于“立即可检索”
- 但如果状态已经是 `SUCCESS`，理论上应该已经完成向量化

### 2. 检查 MySQL 中的文档状态和切片

通过服务器查询确认：

- `document_file` 中目标文档状态为 `SUCCESS`
- `rag_unit` 中已存在完整层级节点
  - `LEAF = 830`
  - `SECTION_SUMMARY = 139`
  - `DOC_SUMMARY = 1`

结论：

- 文档不是没处理
- 切片和层级摘要都已经落库

### 3. 检查 Redis 向量索引

通过服务器查询确认：

- Redis 中存在两套索引
  - `atguigu-index`
  - `rag-summary-index`
- `FT.INFO` 显示索引中有文档
  - 叶子索引 `num_docs = 831`
  - 摘要索引 `num_docs = 144`
- 直接 `JSON.GET` 某条向量文档时，可以看到 metadata 确实写进了 JSON
  - `user_id`
  - `filename`
  - `source_id`
  - `node_type`

结论：

- 不是没写 Redis
- 不是没向量化

### 4. 检查 Redis 索引 schema

通过 `FT.INFO` 发现索引 schema 只包含：

- `content`
- `embedding`

缺少运行时检索依赖的 metadata 字段：

- `user_id`
- `filename`
- `source_id`
- `node_type`
- 以及其他树结构字段

同时实际验证：

- `FT.SEARCH atguigu-index "@filename:(*中医临床诊疗术语*)"` 返回 `0`
- `FT.SEARCH rag-summary-index "@filename:(*中医临床诊疗术语*)"` 返回 `0`

但直接 `JSON.GET` 同一批文档时，又能看到 `filename` 和 `user_id` 已经存在于 JSON 本体中。

结论：

- metadata 被写进了 Redis JSON 文档
- 但没有被注册进 RediSearch 索引 schema
- 所以这些字段不能参与过滤和检索

## 根因

根因是 `VectorStoreConfig` 创建 `RedisVectorStore` 时，没有显式声明 metadata schema。

结果是：

- Redis 索引只索引了默认字段 `content` 和 `embedding`
- 应用侧虽然在 metadata 里写入了 `user_id/filename/source_id/node_type`
- 但 `RagRetrievalService` 使用 `user_id` 做过滤时，底层索引并没有这个字段

这会导致：

- 用户级知识隔离检索不可靠
- 按文件名或其他 metadata 做诊断查询也查不到
- 表现上像“文档明明成功了，但 RAG 没命中”

## 修复内容

已修改文件：

- [VectorStoreConfig.java](D:\rag\demo\src\main\java\com\example\demo\Config\VectorStoreConfig.java)

修复方式：

- 为叶子索引和摘要索引统一显式注册 metadata schema
- 新增字段：
  - `source_id` `TAG`
  - `source_type` `TAG`
  - `unit_id` `TAG`
  - `user_id` `TAG`
  - `node_type` `TAG`
  - `parent_id` `TAG`
  - `filename` `TEXT`
  - `title` `TEXT`
  - `tree_level` `NUMERIC`
  - `child_count` `NUMERIC`
  - `chunk_index` `NUMERIC`
  - `start_time` `NUMERIC`
  - `end_time` `NUMERIC`

新增测试：

- [VectorStoreConfigTest.java](D:\rag\demo\src\test\java\com\example\demo\Config\VectorStoreConfigTest.java)

测试验证：

- 向量存储构建时，确实包含 `user_id/source_id/filename/node_type/chunk_index` 等关键 metadata 字段

## 解决方案

### 代码层

部署本次修复后的代码。

### 数据层

由于旧的 Redis 索引 schema 已经错误创建，单纯发版还不够，必须重建索引。

推荐做法：

1. 删除旧知识库数据
2. 删除旧 Redis 向量索引
3. 重启应用，让新 schema 生效
4. 重新上传文档

## 本次执行

按当前需求，已准备执行知识库数据清理，范围包括：

- MySQL
  - `rag_unit`
  - `document_file`
- Redis
  - `atguigu-index`
  - `rag-summary-index`
  - 对应前缀下的向量 JSON 文档
- MinIO
  - `rag-knowledge` 桶中的对象

## 结论

本次故障不是“没向量化”，也不是“没写 Redis”，而是：

**Redis 向量索引 schema 未注册 metadata 字段，导致检索阶段依赖的 `user_id` 等过滤字段不可用。**

修复后，重新建索引并重新上传文档，即可恢复按用户隔离的 RAG 检索能力。
