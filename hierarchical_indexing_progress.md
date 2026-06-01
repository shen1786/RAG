# 三层摘要树层次化索引改造进度说明

## 这次已经做了什么

### 1. 补齐了层次化索引的核心模型
- 新增 `RagNodeType`，定义三类节点：
  - `LEAF`
  - `SECTION_SUMMARY`
  - `DOC_SUMMARY`
- 扩展了 `RagUnit` 模型，增加了层次索引需要的字段：
  - `nodeType`
  - `title`
  - `parentId`
  - `treeLevel`
  - `ordinal`
  - `childCount`
- 扩展了检索结果对象：
  - 新增 `RetrievalMode`
  - 新增 `HierarchyHit`
  - `RetrievalResult` 现在可以表达“当前是层次检索还是平铺降级检索”，也能承载后续的层级命中路径

### 2. 补齐了层次化索引配置
- 新增 `HierarchyConfig`，统一承载层次索引参数：
  - `enabled`
  - `midChildCount`
  - `summaryCandidateTopK`
  - `midRerankTopK`
  - `leafRerankTopK`
  - `rebuildOnStartup`

### 3. 把向量存储拆成了两套
- 新增 `VectorStoreConfig`
- 现在准备使用两套 Redis VectorStore：
  - `leafVectorStore`
  - `summaryVectorStore`
- 这样后续检索时可以明确区分：
  - 叶子原文向量
  - 摘要层向量

### 4. 新增了摘要树构建入口
- 新增 `HierarchicalIndexingService`
- 它的职责是把 processor 产出的叶子分片统一包装成三层结构：
  - 先规范化叶子节点
  - 按固定窗口构造中层摘要节点
  - 再构造文档根摘要节点
- 当前已实现的行为：
  - 给叶子节点补 `id/sourceId/nodeType/treeLevel/ordinal`
  - 按 `midChildCount` 分组
  - 建立父子关系
  - 输出完整节点列表

### 5. 新增了摘要生成服务
- 新增 `HierarchySummaryService`
- 使用单独的 `summaryChatClient` 调 LLM 生成：
  - 小节标题与摘要
  - 文档级标题与摘要
- 实现方式：
  - 强制要求模型只输出 JSON
  - 结构固定为 `{"title":"...","summary":"..."}`
  - 如果模型输出异常或解析失败，自动回退到规则摘要

### 6. 调整了入库总服务的职责边界
- 重写了 `RagUnitService`
- 现在它已经具备这些新能力：
  - 按“叶子数”而不是“总节点数”统计 chunk
  - 区分叶子节点和摘要节点
  - 支持双向量库写入
  - 支持按 `sourceId` 清理旧索引数据
  - 构建更完整的向量 metadata
- 同时保留了原有上传、检查文件是否存在、查文档状态、异步处理、删除入口等对外能力

### 7. 新增了重建状态
- 在 `DocumentFileStatus` 里增加了 `REINDEXING`
- 并让它进入 `isProcessing()` 判定
- 这是为后续“应用启动自动重建旧文档索引”做准备

## 我是怎么做的

### 1. 先改数据结构，再改服务边界
- 这次没有直接先改检索逻辑
- 我先把“层级节点长什么样”定义清楚
- 然后再把“谁负责构树、谁负责写向量、谁负责清索引”拆干净
- 这样后续接 `FileProcessConsumer` 和 `RagRetrievalService` 时会更稳定

### 2. 用统一节点表承载三层结构
- 没有新开第二张树表
- 仍然围绕 `rag_unit` 工作
- 只是让 `RagUnit` 既能表示叶子原文，也能表示中层摘要和文档根摘要

### 3. 摘要树构建和文档解析彻底分离
- processor 仍然只负责把原始文档解析成叶子内容
- 摘要树构建统一放到 `HierarchicalIndexingService`
- 这样 PDF / Word / PPT / 文本 / 视频不需要各自重复实现摘要树逻辑

### 4. LLM 摘要做了兜底
- 模型优先输出结构化 JSON
- 解析失败时不会中断整条链路
- 会自动回退成“截断式摘要”
- 这样能保证入库过程在模型偶发不稳定时也能继续

### 5. 向量库拆分为叶子和摘要两层
- 这是为了支持后续的检索路径：
  - 先检索摘要层
  - 再向下展开叶子层
- 如果不拆，摘要节点和叶子节点混在同一个向量库里，后续控制检索路径会很别扭

## 目前还没接完的部分

下面这些改造我还在继续接：

- `FileProcessConsumer`
  - 还需要正式接入 `HierarchicalIndexingService`
  - 还需要按叶子节点/摘要节点分别写入两套向量库
- `RagRetrievalService`
  - 还需要从“平铺向量检索”改成“摘要召回 -> 中层展开 -> 叶子 rerank -> 平滑降级”
- 删除链路
  - 还需要把摘要向量和叶子向量一起清理
- 启动自动重建
  - 还需要补 `ApplicationReadyEvent` + 后台异步重建服务
- 配置与 schema
  - 还需要把 `application.yaml` 和 `schema.sql` 同步补齐
- 测试
  - 还需要补层次树构建和检索降级路径的测试

## 这次已经改动到的关键文件

- `src/main/java/com/example/demo/model/RagUnit.java`
- `src/main/java/com/example/demo/model/RagNodeType.java`
- `src/main/java/com/example/demo/model/DocumentFileStatus.java`
- `src/main/java/com/example/demo/model/dto/RetrievalResult.java`
- `src/main/java/com/example/demo/model/dto/RetrievalMode.java`
- `src/main/java/com/example/demo/model/dto/HierarchyHit.java`
- `src/main/java/com/example/demo/Config/HierarchyConfig.java`
- `src/main/java/com/example/demo/Config/VectorStoreConfig.java`
- `src/main/java/com/example/demo/Config/Aiconfig.java`
- `src/main/java/com/example/demo/service/HierarchySummaryService.java`
- `src/main/java/com/example/demo/service/HierarchicalIndexingService.java`
- `src/main/java/com/example/demo/service/RagUnitService.java`
- `src/main/java/com/example/demo/mapper/DocumentFileMapper.java`
- `src/main/java/com/example/demo/service/DocumentFileService.java`

## 当前结论

这一轮已经把“三层摘要树”的底座搭起来了，重点完成了：
- 节点模型
- 摘要生成
- 构树服务
- 双向量库配置
- 入库服务的职责重构

也就是说，现在“怎么表示层次索引、怎么生成摘要、怎么组织成树、怎么准备写入两套向量库”这些核心问题已经落地。

下一步的重点就是把这些能力继续接到：
- 异步文件处理链路
- 实际检索链路
- 自动重建链路
- 删除与测试链路

## 评审注释（补充）

### 这套摘要树做得比较好的地方
- 方向是对的：不是只做“多一层摘要”，而是把节点模型、构树、摘要生成、向量入库、检索结果表达拆成了独立职责。
- 数据模型比较扎实：`RagUnit` 上补齐了 `nodeType / parentId / treeLevel / ordinal / childCount`，说明这套层次结构已经能被稳定存储和查询。
- `HierarchicalIndexingService` 单独负责构树，这一点很好。这样 processor 继续只做“文档解析 -> 叶子切片”，不会把 PDF / Word / PPT 的业务逻辑和摘要树逻辑耦合在一起。
- 叶子向量库和摘要向量库分离是一个正确决定。后续如果要做“先召回摘要，再下钻叶子”的检索路径，这个拆分会比单库混存干净很多。
- `HierarchySummaryService` 给 LLM 输出加了 JSON 约束，并提供了解析失败时的 fallback，这种设计比较工程化，不会因为模型偶发不稳定把整条入库链路打断。

### 当前还存在的明显缺口
- 现在完成的是“底座”，不是“端到端可用”。文档里这一点写得比较诚实。
- `FileProcessConsumer` 如果还没有正式接 `HierarchicalIndexingService` 和双向量库写入，那么异步主链路实际上仍然可能跑的是旧的平铺模式。
- `RagRetrievalService` 如果还没有改成“摘要召回 -> 中层展开 -> 叶子 rerank -> 平滑降级”，那这棵树目前更多是“已建好但还没真正用于检索”。
- 目前的中层节点是按固定窗口分组，不是按文档天然结构分组。这样实现简单，但摘要边界可能不稳定，尤其在长文档、章节明显或段落结构不均匀的情况下会影响摘要质量。
- 现在是固定三层结构。这个方案适合先落地，但后面如果文档长度跨度很大，可能会遇到“短文档层级过重、长文档层级不够”的问题。

### 我对实现方式的判断
- 这是一个偏“先把基础设施搭好，再逐步替换主流程”的改造方案。
- 它不是一次性重写检索系统，而是先把存储模型、树结构、摘要服务、状态机和向量库边界理顺，再逐步接入异步处理和检索链路。
- 这种推进方式比较稳，风险比“直接改检索主链路”低，也更容易逐步回滚和验证。

### 建议优先级
- 第一优先级：把 `FileProcessConsumer` 正式接入层次化构树和双向量入库，否则这套设计还停留在同步路径或局部实现。
- 第二优先级：改 `RagRetrievalService`，真正把检索从平铺模式切到层次模式，并保留 flat fallback。
- 第三优先级：补删除链路和测试。因为一旦开始双库和多节点类型并存，删除不彻底和回归缺失会很容易留下脏数据。
- 第四优先级：再考虑把“固定窗口分组”升级为“按标题、段落或语义边界分组”，这样摘要树质量会明显提升。

### 一句话结论
- 这份方案不是空想，已经把三层摘要树的核心底座搭起来了。
- 但它当前更像“层次化索引基础设施已完成 60%~70%”，还不能说“层次化检索已经真正跑通”。
