# feat: 滑动窗口 + 累积摘要三层聊天记忆系统

## 背景

原有实现使用 Spring AI 的 `MessageWindowChatMemory`，配置 `maxMessages(10)`。

**核心问题：** `MessageWindowChatMemory` 在每次对话时会读取 Redis 全部消息 → 裁剪到最近 N 条 → **写回 Redis**，导致旧消息被永久删除，无法恢复。

```
第 11 轮: Redis 读出 11 条 → 裁剪 → 写回 [msg2..msg11]
                                            ↑ msg1 永久消失
```

影响：
- 用户查看历史对话只能看到最近 10 条
- 画像提炼只能基于剩余的 10 条消息
- 前端刷新页面后更早的对话消失

**已知残留限制：** `QueryRewriteService.rewrite()` 仍然直接从 `RedisChatMemoryRepository` 读取窗口消息（最多 6 条），未使用完整历史或摘要。当前查询改写的上下文窗口与模型上下文窗口独立，后续可考虑将摘要也注入改写阶段。

## 方案

自定义 `SummaryWindowChatMemory` 替换 `MessageWindowChatMemory`，实现三层记忆架构：

| 层 | 存储 | 作用 | 是否丢失 |
|---|---|---|---|
| 完整聊天记录 | Redis List: `session:full-history:{sessionId}` | 前端展示、画像提炼 | 永不丢失 |
| 会话摘要 | Redis String: `session:summary:{sessionId}` | 旧消息压缩注入模型上下文 | 会话删除时清理 |
| 窗口消息 | Repository: `spring:ai:chat:memory:{sessionId}` | 模型直接读取的最近 N 条 | 由 `SummaryWindowChatMemory.add()` 主动写入，删除时由 `chatMemory.clear()` 清理 |

## 实现细节

### 核心类：`SummaryWindowChatMemory`

实现了 Spring AI 的 `ChatMemory` 接口，与 `MessageChatMemoryAdvisor` 完全兼容。

**`add()` 方法流程：**
1. 将新消息追加到 Redis List（完整历史，永不裁剪）
2. 检查完整历史条数是否超过 `summarize-threshold`
3. 如果超过，取**上次摘要之后新进入窗口外的消息**（增量），结合旧摘要，调用 LLM 生成新摘要
4. 将窗口内的最近 N 条消息保存到 Repository

**摘要增量机制：** 摘要存储为 JSON `{"summary": "...", "count": N}`，其中 `count` 记录已摘要覆盖的消息条数。每次触发时只传 `count` 到窗口边界之间的新消息给 LLM，成本恒定，不会随会话增长而线性增加。

**`get()` 方法流程：**
1. 从 Repository 读取窗口内消息
2. 从 Redis 读取会话摘要（解析 JSON 中的 `summary` 字段）
3. 返回：`[摘要 SystemMessage] + [最近 N 条原始消息]`

**`clear()` 方法：** 统一清理三层存储（完整历史 + 摘要 + 窗口消息）。

### 摘要格式

```text
【用户目标】用户想要做什么
【已确认事实】已确认的关键事实
【关键结论】已得出的结论
【未解决问题】尚未解决的问题
【用户偏好/约束】用户的偏好或限制条件
```

摘要使用已有的 `summaryChatClient`（无 Memory Advisor 的独立 ChatClient）生成，失败时降级不影响主流程。

### 数据流

```text
用户发消息
    │
    ├─ MessageChatMemoryAdvisor 调用 chatMemory.add(sessionId, [userMsg])
    │   ├─ RPUSH session:full-history:{id}  ← 完整历史永久保存
    │   ├─ 如果条数 > summarizeThreshold:
    │   │     读旧摘要 + 本次新淘汰的消息（增量） → LLM → 写入 session:summary:{id}
    │   └─ saveAll 到 Repository（仅最近 N 条）
    │
    ├─ QueryRewriteService.rewrite() → 从 Repository 读最近 6 条改写查询
    │   （注意：这里读的是窗口消息，不是完整历史，见"已知残留限制"）
    │
    ├─ RAG 检索
    │
    ├─ MessageChatMemoryAdvisor 调用 chatMemory.get(sessionId)
    │   └─ 返回 [摘要, 最近N条] → 拼入模型上下文
    │
    └─ 模型生成回复 → Advisor 调用 chatMemory.add(sessionId, [assistantMsg])
        └─ 重复上述流程
```

### Redis Key 设计

| Key | 类型 | 说明 | 写入方 | 清理方 |
|---|---|---|---|---|
| `session:full-history:{sessionId}` | List | 完整聊天记录（每条 JSON 带时间戳） | `SummaryWindowChatMemory.add()` | `SummaryWindowChatMemory.clear()` |
| `session:summary:{sessionId}` | String | JSON `{"summary":"...", "count":N}` | `SummaryWindowChatMemory.add()` | `SummaryWindowChatMemory.clear()` |
| `spring:ai:chat:memory:{sessionId}` | String | 窗口内消息 | `SummaryWindowChatMemory.add()` (via repository.saveAll) | `SummaryWindowChatMemory.clear()` + `SessionManager.deleteSession()` 双重清理 |
| `user:profile:{userId}` | String | 跨会话用户画像 | `UserProfileService` | 长期保留，不清 |

> **关于双重清理：** `spring:ai:chat:memory:{sessionId}` 有两处删除入口——`chatMemory.clear()` 和 `SessionManager.deleteSession()`。这是有意为之：`chatMemory.clear()` 确保通过 `AiService.deleteSession()` 路径时完整清理；`SessionManager.deleteSession()` 作为兜底，防止绕过 `AiService` 直接调用时遗留脏数据。两者幂等，重复删除无副作用。

## 变更文件

| 文件 | 操作 | 说明 |
|---|---|---|
| `src/main/resources/application.yaml` | 修改 | 新增 `chat.memory.*` 可配置参数 |
| `src/main/java/.../Config/SummaryWindowChatMemory.java` | 新建 | 核心实现，~250 行 |
| `src/main/java/.../Config/Aiconfig.java` | 修改 | 替换 `MessageWindowChatMemory`，新增参数校验 |
| `src/main/java/.../service/AiService.java` | 修改 | `getHistory` 读完整历史；`deleteSession` 统一清理 |
| `src/test/java/.../Config/SummaryWindowChatMemoryTest.java` | 新建 | 9 个测试用例 |
| `src/test/java/.../Controller/AiControllerTest.java` | 修改 | 适配新类型 |

## 可配置参数

```yaml
chat:
  memory:
    max-messages: 10              # 传给模型的最近消息窗口大小
    summarize-threshold: 12       # 超过多少条触发摘要
    assistant-truncate-length: 300 # 摘要时截断助手回复的字符数
    summary-max-length: 500       # 摘要最大字数限制
```

**参数约束：** `summarize-threshold` 应当大于 `max-messages`，否则窗口永远不会溢出，摘要不会触发。启动时会校验此约束，不满足则抛出 `IllegalArgumentException` 阻止启动。

调参建议：
- `max-messages`：越大模型上下文越完整，但 Token 消耗越高
- `summarize-threshold`：与 `max-messages` 的差值决定摘要触发频率，差值越小触发越频繁
- 生产环境建议 `max-messages` 不超过 20，`summarize-threshold` 为 `max-messages + 2~4`

## 测试验证

```bash
# 运行本次改动相关测试
mvn test -Dtest="SummaryWindowChatMemoryTest,AiServicePromptTest,AiControllerTest"

# 手动验证：连续发 N 轮消息（每轮 1 user + 1 assistant = 2 条）
redis-cli LLEN session:full-history:{sessionId}    # 应该 = 2N
redis-cli GET session:summary:{sessionId}           # 应该有 JSON 格式的摘要
redis-cli GET user:profile:{userId}                 # 应该有用户画像

# 删除会话后验证清理
redis-cli EXISTS session:full-history:{sessionId}   # 应该 = 0
redis-cli EXISTS session:summary:{sessionId}        # 应该 = 0
redis-cli EXISTS spring:ai:chat:memory:{sessionId}  # 应该 = 0
redis-cli GET user:profile:{userId}                 # 应该仍然存在
```
