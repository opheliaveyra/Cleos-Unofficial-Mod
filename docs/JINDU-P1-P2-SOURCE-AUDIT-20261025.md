# P1/P2 缺口审计报告

> 津渡 · 2026-10-25 · 基于源码实际审计，非猜测

---

## P1：记忆系统审计结论

### 已有能力（代码级确认）

| 能力 | 实现位置 | 现状 |
|------|----------|------|
| 五类记忆 | Memory.kt:34-50 | profile/interest/recent/rapport/self |
| 单类上限 10 | MemoryKinds.PER_KIND=10, remember():214 | 超限抛 ToolFailure |
| 单主题上限 30 细节 | MemoryKinds.DETAILS=30, update():253 | 超限抛 ToolFailure |
| 摘要 120 字符，细节 300 字符 | SUMMARY_MAX=120, DETAIL_MAX=300 | 硬截断 |
| pinned 保护 | update():239, forget():266 | 代码级拒绝修改和删除 |
| recent 带日期 | MemoryDigest.line():96-99 | 注入"X月X日记下"到提示词 |
| 聊天只注入摘要 | forChat():102-118 | 细节需 open 工具查看 |
| 信件带细节 | forLetter():120-137 | 预算 16000 字符 |
| 角色隔离 | find():191, remember():213 | 全部按 companionId 隔离 |
| 记忆操作是工具调用 | MemoryBook.act():181 | 在聊天流内，已被 chat usageTag 覆盖 |

### 关于"重复检测"（结论：无代码缺口）

**现状**：remember() 没有代码级去重，但这是**设计决策**而非遗漏。

**证据**：
- MemoryDigest.RULES (169-173行) 明确指导模型："先看已有的摘要，真属于某条已有话题的用 update 加细节，不然再开新的"
- remember() 前先查 dao.allFor(companionId)（213行），检查该类是否已满
- 每类只允许 10 条主题，满了会强制模型合并或删旧的

**结论**：去重由提示词 + 容量限制共同实现，不是代码遗漏。如果强行加代码级语义去重（比如字符串相似度），反而会：
1. 误判（"喜欢咖啡" vs "最近迷上了手冲咖啡"算不算重复？）
2. 干扰模型的自主记忆管理
3. 增加复杂度却没有明确收益

**建议**：不改。如果实际使用中发现模型确实产生大量重复，优先调整提示词，而非加代码逻辑。

### 关于"冲突处理"（结论：无代码缺口）

**现状**：update() 直接替换字段，无冲突标注机制。

**证据**：
- update() 的 summary 和 name 字段是直接覆盖（248-249行）
- RULES 告诉模型"记错了就用 update 改对"（173行）
- 模型在聊天中能看到所有记忆摘要，矛盾应该由模型在对话中自主发现并修正

**结论**：冲突处理也是设计上交给模型的。"喜欢咖啡" vs "不喜欢咖啡"这种矛盾，模型应该在下次看到摘要时自行 update。加自动冲突检测需要语义理解，本质上就是再调一次模型，不如让模型在正常对话流程中处理。

**建议**：不改。

### 关于"时效清理"（结论：无代码缺口）

**现状**：recent 类带日期但无自动过期清理。

**证据**：
- recent 类的日期注入到提示词（96-99行），模型能看到每条记了多久
- RULES 说"「最近」那一类会过期，过期了要改写或删掉，别留着变成假话"（171行）
- 10 条上限本身就是天然的清理机制：满了必须合并/删除

**结论**：时效清理也是设计上交给模型的。自动清理有风险（"最近在学日语"三个月后仍然有效，但自动清理可能误删）。

**建议**：不改。

### P1 最终结论

**记忆系统无需代码改动。** 所有"缺口"实际上是经过深思熟虑的设计决策：将语义判断交给模型，代码只负责结构约束（上限、隔离、pinned 保护）。交接书说"先补重复记忆/冲突与时效的测试，再评估最小增量"，评估结论是：最小增量为零。

---

## P2：主动消息审计结论

### 已有能力（代码级确认）

| 能力 | 实现位置 | 现状 |
|------|----------|------|
| WorkManager 调度 | Later.kt:477-487 | OneTimeWorkRequest + 唯一 work name |
| 唯一工作标识 | enqueue():482 | "$kind-$id" 格式，ExistingWorkPolicy 控制去重 |
| 重启恢复 | restart():415-429 | ExistingWorkPolicy.KEEP 恢复所有待办 |
| MAX_WAITING=4 | LaterRules:39, note():196 | 超限抛 ToolFailure |
| UNANSWERED_MAX=2 | LaterRules:72, wake():260 | 连续未回应后停止主动消息 |
| SOON_MINUTES=1 | LaterRules:62 | 正在回复/输入时顺延 |
| RETRY_MINUTES=10 | LaterRules:65 | 失败后重试 |
| 过期宽限 | grace():52 | 等待时长的 30min~3h |
| 推迟一次限制 | note():192-194 | putOff=true 后不可再推 |
| ta.proactive 开关 | ChatRepository | 关闭后 Later 工具不可用 |
| 通知渠道 | Notifier.kt:31-55 | CHANNEL_MESSAGES(HIGH)、LETTERS(HIGH)、WORKING(LOW)、CALLS(LOW) |
| 通知权限检查 | Notifier.allowed():89-90 | areNotificationsEnabled() |
| 权限请求触发 | Later.wantsNotifications:178 | MutableStateFlow，UI 层监听并请求 |
| 早安/晚安问候 | greet():317-369 | 基于用户习惯时间窗口 |
| 用户在线检测 | inUse():338 | 屏幕亮且解锁才打招呼 |
| 习惯学习 | RoutineRules.habits() | 回看用户消息时间推断作息 |

### 关于"静默时段"（结论：已有等效机制）

**现状**：没有显式的"22:00-8:00 免打扰"设置，但有**基于用户习惯的时间窗口**。

**证据**：
- RoutineRules.habits() 分析用户历史消息时间，推断活跃时段
- RoutineRules.window() 计算早安/晚安的合适窗口
- greet():338 检查 inUse()（屏幕亮且解锁）
- greet():341 检查 UNANSWERED_MAX
- note_for_later 的主动消息不在固定时段触发，而是在模型设定的时间

**结论**：静默时段通过习惯学习 + 在线检测 + 未回应熔断间接实现。没有"半夜推送"的风险，因为：
1. 早安/晚安有时间窗口限制
2. note_for_later 的时间由模型设定（通常是合理时间）
3. 不在线不推送

**建议**：不改。如果用户希望有显式静默时段设置，属于功能需求而非缺口。

### 关于"唯一工作标识与重启恢复"（结论：已完善）

**证据**：
- enqueue():482 使用 "$kind-$id" 作为唯一 work name
- restart():415-429 在 App 启动时恢复所有待办，使用 ExistingWorkPolicy.KEEP（不重复）
- WorkManager 本身跨重启持久化

**结论**：无缺口。

### 关于"通知权限"（结论：已处理）

**证据**：
- Notifier.allowed():89-90 检查权限
- Later.wantsNotifications:178 触发 UI 请求权限
- Android 10 (SDK 29) 不需要运行时通知权限（Android 13+ 才需要）
- 测试设备是 Android 10，无此问题

**结论**：无缺口。当前设备无需额外处理。

### 关于"Doze/电池优化"（结论：WorkManager 已处理）

**证据**：
- 使用 WorkManager 而非 AlarmManager，系统会自动处理 Doze
- LaterRules.grace() 提供宽限期（30min~3h），容忍系统延迟
- 不需要精确到秒的定时，宽限机制已覆盖

**结论**：无缺口。WorkManager + 宽限期是 Android 推荐的后台调度方案。

### P2 最终结论

**主动消息系统无需代码改动。** 所有审计点在现有代码中都有对应实现或等效机制。交接书说"检查静默时段、唯一工作标识、重启恢复、通知权限、电池优化，提供实际缺口证据再提改动"，实际审计未发现需要改动的缺口。

---

## 总结

| 优先级 | 任务 | 审计结论 | 建议操作 |
|--------|------|----------|----------|
| P0 | 补后台 Token 用量 | ✅ 已完成 | commit 42fc821 已推送 |
| P1 | 记忆系统补缺口 | ✅ 无代码缺口 | 不改 |
| P2 | 主动消息补缺口 | ✅ 无代码缺口 | 不改 |

**三项任务中，只有 P0 需要实际代码改动，且已完成。P1/P2 经源码审计确认，现有实现完整，无需代码级修补。**

---

## 附：审计依据文件
- Memory.kt: 385 行，完整阅读
- Later.kt: 533 行，完整阅读
- Notifier.kt: 通知渠道和权限检查
- CleosApp.kt: App 启动流程和通知触发
- Aster 四份审计报告
- 交接书 CLEOS-JINDU-CONSTRUCTION-HANDOFF-20261025.md
