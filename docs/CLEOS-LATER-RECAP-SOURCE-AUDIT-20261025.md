# Cleos 主动提醒与会话摘要源码审计（2026-10-25）

范围：仅源码核查与交接，未修改业务代码、未构建、未真机验证。
审计分支：`feature/chat-file-upload-and-hold-to-talk`。

## 1. 发现：主动提醒已具备关键防打扰机制
源码：`app/src/main/java/com/cleo/cleos/ai/Later.kt`
- `LaterRules.MAX_WAITING=4`：单角色待办上限。
- `MIN_MINUTES=1`，`MAX_MINUTES=7*24*60`：提醒时间范围。
- `grace(waitMs)`：过期宽限30分钟至3小时。
- `SOON_MINUTES=1`：正在回复或用户正在输入时顺延。
- `RETRY_MINUTES=10`：请求失败后重试。
- `UNANSWERED_MAX=2`：连续两条未回应的主动消息后，等待用户再次发言。
- 采用 `WorkManager` / `LaterWorker` 安排一次性工作；并记录唤醒状态。
- `ChatRepository.kt` 只在 `ta.proactive` 为真时开放 `ToolGroup.Later`，并处理到期记录。
**结论：禁止重写主动消息调度器或重复实现频率熔断。** 应继续审查静默时段、重启后的唯一任务键、通知权限/渠道、Doze/电池优化和真实设备行为。源码存在相关机制不代表已在真机验证。

## 2. 发现：会话前情提要已有滚动压缩与缓存优化
源码：`app/src/main/java/com/cleo/cleos/ai/Recap.kt`
- `LENGTH=1500`：提示词要求摘要长度；`MAX_STORED=4000`：存储上限。
- `MAX_FOLD=150`：单次折叠最多150条；`CATCH_UP=600`：旧对话补救窗口。
- `step(window)=maxOf(10,window/2)`，成批折叠以保持提示词前缀缓存。
- `toFold` 尽量以用户消息边界分割，避免拆散问答。
- `Recaps.foldLater` 在后台执行；`registry.route(Feature.Recap)` 选择摘要模型；`client.stream` 调用模型。
- `foldRecap` 仅在摘要未被其他操作编辑时提交，避免覆盖用户修改。
**结论：不需要引入另一套长聊天摘要系统。** 更有价值的是补后台调用的用量统计、摘要失败可观测性和回归测试。

## 3. 推荐津渡优先实施的最小变更
1. 先审计 `Recaps.foldLater` 的 `client.stream` 与 `TokenUsageEntity` 记录链路，补齐 recap 用量（服务商不返回 usage 时记 source=missing，绝不伪造0 token）。
2. 检查 `Memory.kt` 中模型调用是否漏记 purpose=memory；用同一套统计约定。
3. 补测试：成功/失败/无 usage、角色与全局 scope、重试不重复计数；验证数据库迁移和备份兼容。
4. 后续再调查 Later 静默时段、Android 13+ 通知权限、后台调度准确性。不得把已有 MAX_WAITING/UNANSWERED_MAX 再做一遍。

## 4. 与前两份报告的修正
- GitHub 项目 Mem0 仅参考记忆冲突/召回思想；Cleos 已有分层记忆与滚动 recap。
- 主动消息的冷却/熔断并非全部缺失；Later.kt 已有部分防打扰和重试逻辑。
- 当前阶段以“修补可测的缺口”优先于新增第三方依赖。

后续第四次提交：核对 TokenUsage 写入点与各 purpose 覆盖表。
第五次提交：汇总成津渡可直接执行的最小施工包与验收表。
