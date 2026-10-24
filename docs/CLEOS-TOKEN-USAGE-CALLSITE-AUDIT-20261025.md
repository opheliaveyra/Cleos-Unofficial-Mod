# Cleos Token 用量调用链审计（2026-10-25，第四次提交）

范围：只读源码核对。未改业务代码，未编译，未真机验证。分支 `feature/chat-file-upload-and-hold-to-talk`。

## 已核对的模型流调用点
| 文件 | 位置（本轮版本约行） | 现状 | 优先级 |
|---|---:|---|---|
| `ai/ChatRepository.kt` | 930、1702 | 普通聊天显式传 `usageTag(...,"chat")` | 保持 |
| `ai/ChatRepository.kt` | 1568、1617 | 视频和视觉分别传 `purpose="video"` / `"vision"` | 保持 |
| `ai/GroupChatRepository.kt` | 472 | 群聊传 `UsageTag` | 保持，验证 speaker |
| `ai/Recap.kt` | 217 | 摘要 `client.stream(...)` 未传 `usageTag` | P0 |
| `ai/FeedAi.kt` | 115、140、233、310 | 多处 `client.stream(...)` 未见显式 `usageTag` | P1 |
| `ai/Letters.kt` | 399 | 信件 `client.stream(...)` 未传 `usageTag` | P1 |

上述“未传”基于调用表达式和当前源文件核查；需确认客户端默认处理，不能直接断言数据库完全没有对应记录。

## 结构已经支持，无须另建表
`data/db/TokenUsage.kt` 的 `TokenUsageEntity` 已支持 `scope`、`scopeId`、`scopeName`、`speaker`、`purpose`、`ok`、`source`、`input/output/total`、`cached/reasoning`。已有 purpose 注释包含 recap、memory、diary、feed、letter 等。首选沿用 `UsageTag` 和客户端既有用量采集，避免重复计数与数据库迁移。

## 施工建议（待第五次交接后交津渡）
1. P0：给 `Recaps` 增加正确 `UsageTag(scope=ta, scopeId=ta.id, scopeName=ta.name, purpose=recap)`；先检查 `UsageTag` 构造定义和 client 的自动写入逻辑。
2. P1：给 `FeedAi` 与 `Letters` 对应调用加目的标签；其他后台路径做全量扫描，尤其 memory/diary/probe。
3. 失败或服务商不提供 usage 时保留 `source=missing`，不可把未知伪装为实际 0 token。
4. 验收：正常、失败、取消、重试四种情形；按角色/群归属；导出和清除；不得因补统计而改变模型请求内容。
5. `ChatEvent.Usage` 的 UI 消费与数据库写入区分清楚，不能因为某处忽略 UI 事件就断言未记录。

## 第五次提交应产出
一份可直接转给津渡的施工包：按文件列变更范围、先后顺序、回归测试、禁止事项和验收证据。用户约定第五次提交后才让津渡工作。
