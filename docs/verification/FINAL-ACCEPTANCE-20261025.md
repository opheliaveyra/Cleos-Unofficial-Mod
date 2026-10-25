# 最终功能验收报告 FINAL-ACCEPTANCE-20261025

> 验收人：津渡  
> 日期：2026-10-25  
> APK 版本：0.1.99-mod-p0test (versionCode 299)  
> 基于 commit：42fc821 (P0) + 60514ee (验证) + e9aa865 (审计)  
> 测试设备：HONOR YAL-AL50, Android 10 (SDK 29), serial NDYDU19830006567  
> 模型：DeepSeek deepseek-v4-flash（入梦 Flash）

---

## 一、P0：后台 Token 用量统计

### 代码验证 ✅ 通过

| 文件 | 修改点 | usageTag 参数 | 验证方式 |
|------|--------|--------------|----------|
| Recap.kt:217 | stream() 调用 | scope=SCOPE_TA, purpose="recap" | 代码审查 + 编译通过 |
| FeedAi.kt:115 | write() 的 stream() | scope=SCOPE_TA, purpose="feed" | 代码审查 + 编译通过 |
| FeedAi.kt:140 | planPhoto() 的 stream() | scope=SCOPE_TA, purpose="feed" | 代码审查 + 编译通过 |
| FeedAi.kt:233 | visit() 的 stream() | scope=SCOPE_TA, purpose="feed" | 代码审查 + 编译通过 |
| FeedAi.kt:310 | interact() 的 stream() | scope=SCOPE_TA, purpose="feed" | 代码审查 + 编译通过 |
| Letters.kt:399 | compose() 的 stream() | scope=SCOPE_TA, purpose="letter" | 代码审查 + 编译通过 |

### 编译与安装 ✅ 通过

- assembleRelease: BUILD SUCCESSFUL in 2m 16s
- adb install -r: Success
- App 启动正常，无崩溃
- 版本号确认：dumpsys package 显示 versionCode=299, versionName=0.1.99-mod-p0test

### App 内用量统计页 ✅ 存在且正常工作

- 截图：[p0-usage-stats-page.png](p0-usage-stats-page.png)
- 本月合计 34 次请求，187892 tokens
- 已有 purpose 分布：chat 168215, game_tic_tac_toe 10926, game_gomoku 8751
- 现有统计机制工作正常，UsagePage.kt 正确读取和展示 TokenUsageEntity

### purpose=recap 实测 ⚠️ 未验证

**原因**：Recap 触发条件未满足。
- Recap.toFold() 逻辑：live.size 必须 > window + step(window)
- 当前配置：window=40, step=max(10, 40/2)=20，阈值 = 60 条消息
- 当前会话消息数：37 条（不足 60 条）
- 结论：需要继续聊天至 60+ 条才能触发 Recap 折叠
- 代码路径确认：Recap.kt:217 的 usageTag 参数正确，stream() 调用时会自动记录

### purpose=feed 实测 ⚠️ 未验证

**原因**：FeedAi 功能未触发。
- FeedAi 的 write/planPhoto/visit/interact 需要用户进入"逛逛"(Feed)功能
- 需要配置外部新闻源和触发 TA 创作
- 当前测试环境未配置 Feed 功能
- 代码路径确认：FeedAi.kt 四处 stream() 调用的 usageTag 参数正确

### purpose=letter 实测 ⚠️ 未验证

**原因**：Letters 功能未触发。
- 主页显示"还没有信。写一封给TA？"
- Letters.compose() 在 TA 收到信或需要回信时触发
- 当前无任何信件记录
- 代码路径确认：Letters.kt:399 的 usageTag 参数正确

### P0 结论

**代码正确性：✅ 通过**（编译、安装、运行、现有统计机制正常）  
**实际记录产生：⚠️ 未验证**（recap/feed/letter 三个功能均未在测试期间被触发）  
**根因**：测试环境数据不足（37条消息/0封信/Feed未配置），非代码问题  
**建议**：在日常使用中自然触发后，通过 App 内用量统计页确认对应 purpose 的记录出现

---

## 二、P1：记忆系统行为验证

### 记忆现状
- 主页显示"TA还没记下什么"
- 截图：[home-memory-empty.png](home-memory-empty.png)
- 当前 TA 没有任何记忆记录

### 重复记忆检测 ⚠️ 未验证（记忆为空）

**代码分析**（替代实测）：
- remember() 在插入前查询 dao.allFor(companionId)（Memory.kt:213）
- 检查同类记忆数量是否达到 PER_KIND=10 上限
- 提示词 MemoryDigest.RULES 指导模型"先看已有摘要，属于已有话题的用 update 加细节"
- **设计意图**：语义去重交给模型，代码约束上限

### 旧信息更新 ⚠️ 未验证（记忆为空）

**代码分析**：
- update() 方法（Memory.kt:237-261）支持修改 name/summary/details/category
- 非 pinned 的记忆可自由修改
- RULES 中说"记错了就用 update 改对"

### recent 时效处理 ⚠️ 未验证（记忆为空）

**代码分析**：
- recent 类记忆带日期显示（MemoryDigest.line():96-99）
- 格式："X月X日记下"注入到提示词
- RULES 说"「最近」那一类会过期，过期了要改写或删掉"
- **无自动清理代码**：依赖模型判断

### pinned 保护 ✅ 通过（代码验证）

- update() 第239行：`if (m.pinned) throw ToolFailure("这条是对方钉住的，你改不了")`
- forget() 第266行：`if (m.pinned) throw ToolFailure("这条是对方钉住的，你删不了")`
- forChat() 第115行：pinned 记忆标注"【对方钉住的：不要改，也不要删】"
- **三重保护：代码拒绝 + 提示词标注 + UI 展示**

### 角色隔离 ✅ 通过（代码验证）

- find() 第191行：`dao.get(id)?.takeIf { it.companionId == companionId }`
- remember() 第213行：`dao.allFor(companionId)` 只查当前 TA
- 所有记忆操作严格按 companionId 隔离
- 当前有 2 个 TA（设置页可见），互不干扰

### P1 结论

**代码机制：✅ 完整**（pinned 保护、角色隔离、上限约束）  
**行为验证：⚠️ 大部分未验证**（TA 记忆为空，无法触发去重/更新/时效场景）  
**建议**：在日常使用产生记忆后，观察模型是否正确执行合并/更新/清理

---

## 三、P2：主动消息行为验证

### 主动消息开关 ✅ 确认开启
- 设置页显示"主动找你 开"
- 截图：[settings-proactive-on.png](settings-proactive-on.png)

### WorkManager 调度 ✅ 通过
- dumpsys jobscheduler 显示 2 个 Cleos WorkManager 任务
- Job #10: 延迟 ~19h49m（推算为明天早安问候，约明早 5:10）
- Job #11: 延迟 ~19h49m（推算为明天晚安问候）
- 策略：OneTimeWorkRequest + ExistingWorkPolicy.KEEP/REPLACE
- 状态：RUNNABLE，RUN_ANY_IN_BACKGROUND allowed

### App 重启后待办恢复 ✅ 通过
- Later.restart()（Later.kt:415-429）在 App 启动时恢复所有待办
- 使用 ExistingWorkPolicy.KEEP 避免重复
- WorkManager 本身跨进程重启持久化
- 验证：force-stop 后重新启动，WorkManager 任务仍在（dumpsys 确认）

### 连续未回复停止 ✅ 通过（代码验证）
- UNANSWERED_MAX=2（LaterRules:72）
- wake():260 和 greet():341 均检查 sentSince(ta.id, lastSaid)
- 超过 2 次未回复后停止推送，记录 WakeEntity.HELD
- 用户回复后计数重置

### 夜间通知 ✅ 通过（代码 + 系统验证）
- RoutineRules.habits() 从用户消息时间学习作息（Later.kt:307-309）
- RoutineRules.window() 计算问候时间窗口（Routine.kt:77）
- greet():338 检查 inUse()（屏幕亮且解锁才打招呼）
- note_for_later 的主动消息时间由模型设定
- 通知历史（dumpsys notification）：day=4 有 2 次，day=6 有 17 次，无夜间异常集中

### 通知权限 ✅ 通过
- Notifier.allowed() 检查 areNotificationsEnabled()
- Later.wantsNotifications: MutableStateFlow 触发 UI 请求
- Android 10 不需要运行时通知权限（Android 13+ 才需要）
- dumpsys notification 确认 com.cleo.cleos 通知渠道存在且已发送过

### Doze/电池优化 ✅ 通过（代码验证）
- 使用 WorkManager（非 AlarmManager），系统自动处理 Doze
- grace() 提供 30min~3h 宽限期容忍系统延迟
- dumpsys jobscheduler 显示 RUN_ANY_IN_BACKGROUND allowed

### P2 结论

**系统机制：✅ 全部通过**（WorkManager调度/重启恢复/未回复熔断/习惯学习/通知权限/Doze兼容）  
**长期行为：⏳ 待持续观察**（实际早安/晚安准时性需数天观察）

---

## 四、发现的问题

### 已知问题（非本次引入）

1. **App 闪退记录**：设置页"关于"显示"上次闪退了，记录在这里"——这是安装前就存在的
2. **GitHub 推送不稳定**：git push 经常连接超时/重置，需多次重试或使用 GitHub Upload 插件

### 本次引入的问题

无。P0 代码修改仅添加了 usageTag 参数，不影响任何业务逻辑。

---

## 五、证据清单

| 文件 | 内容 |
|------|------|
| [p0-token-usage-app-running.png](p0-token-usage-app-running.png) | P0 APK 安装后 App 正常启动 |
| [p0-usage-stats-page.png](p0-usage-stats-page.png) | 用量统计页：现有记录正常显示 |
| [p0-usage-baseline.png](p0-usage-baseline.png) | 用量统计基线（安装后首次查看） |
| [settings-proactive-on.png](settings-proactive-on.png) | 设置页：主动找你 开 |
| [home-memory-empty.png](home-memory-empty.png) | 主页：记忆为空 |
| [P0-VERIFICATION-20261025.md](P0-VERIFICATION-20261025.md) | P0 编译安装验证报告 |

---

## 六、构建信息

| 项 | 值 |
|---|---|
| APK 类型 | release (signed) |
| 签名 | cleos-test.keystore, SHA-256: aa665f... |
| versionCode | 299（临时测试用，已还原为 264）|
| versionName | 0.1.99-mod-p0test |
| Gradle | 9.7.1 |
| JDK | 21.0.12.1+1 |
| 编译时间 | 2m 16s (release) |
| 编译环境 | Windows, Android SDK 36 |

---

## 七、GitHub Commits

| SHA | 内容 |
|-----|------|
| 42fc821 | feat(usage): 补齐 Recap/FeedAi/Letters 后台 Token 用量统计 |
| 60514ee | docs(verification): P0 真机验证报告与截图 |
| e9aa865 | docs(audit): P1/P2 缺口审计报告 |
| (待提交) | docs(acceptance): 最终验收报告 |

---

## 八、剩余风险与后续建议

### 剩余风险

1. **P0 token_usage 记录未实际产生**：代码正确但三个后台功能（recap/feed/letter）均未在测试中被触发。理论风险：stream() 的 onUsage 回调在这三个路径上可能有异常被吞掉。概率低（同机制在 chat/game 上已验证工作），但不为零。

2. **记忆系统依赖模型行为**：去重、冲突处理、时效清理均交给模型提示词引导。如果更换模型或模型能力下降，可能出现记忆质量问题。

3. **主动消息时间窗口**：habits() 需要足够历史数据才能准确推断作息。新用户或使用不规律的用户，时间窗口可能不精确。

### 后续建议

1. **自然触发验证**：在日常使用中，聊天超过 60 条后检查用量页是否出现 recap 记录；使用 Feed 功能后检查 feed 记录；收发信件后检查 letter 记录。

2. **版本号**：当前仓库中 versionCode=264，正式发版前需要决定目标版本号。测试用的 299 已还原。

3. **不建议代码改动**：P1/P2 审计确认现有实现完整，当前不需要额外代码修补。
