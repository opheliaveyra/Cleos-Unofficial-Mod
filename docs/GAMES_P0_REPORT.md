# 小游戏中心 P0：井字棋 实现报告

- **当前版本**：`0.1.80-mod`（versionCode 252）
- **安装包**：`C:\Users\15536\.dsh\work\dist\CLEOS-0.1.80-mod.apk`（桌面 `D:\Desktop\CLEOS-0.1.80-mod.apk` 是同一份）
- **最近提交**：`f9c8101`（P0-C 持久化）· `02373c4`（P0-C 优先级一）· `2aa574e`（P0-B）· `c9f81f6`（P0-A）
- **任务书**：`docs/GAMES_P0_WORK_TASK.md`、`docs/GAMES_P0C_DSH_TASK.md`
- **真机**：荣耀 YAL-AL50 / Android 10 / 1080×2340；模型「入梦 Flash」（用户自己的中转站）

## 状态总览

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0-A | 主页入口、邀请真实 TA、本地棋局与胜负规则 | ✅ 完成（真机） |
| P0-B | TA 真的用模型落子（共享 chatClient + 用量标签） | ✅ 完成（真机） |
| P0-C 优先级一 | 请求归属（session/revision/requestId）、防重、严格解析、类型化失败、超时/取消、角色身份 | ✅ 完成（单测 + 真机） |
| P0-C 优先级二 | 棋局持久化、杀进程恢复、「已中断，可继续」、不自动重试付费 | ✅ 完成（真机） |
| 未做 | 角色删除/备份恢复后的稳定身份映射与旧局迁移；旋转/离线/连点的逐项真机 | ❌ 见「已知限制」 |

## 1. 做了什么

**P0-A（0.1.75）**
- 主页「记忆」与「收藏」之间加「小游戏」卡片 → 小游戏中心 → 井字棋；底栏仍四项。
- 「选一个 TA」弹出**真实角色列表**；人执 X 先手、TA 执 O；横竖斜/平局判定、重开。
- 纯 Kotlin 规则 `ui/games/TicTacToe.kt`。

**P0-B（0.1.76–0.1.78）**
- `ui/games/GameAi.kt`：经 `registry.route(Feature.Chat)` 取 TA 的模型，走共享 `chatClient`；
  局面压成 3×3 + 可选数字；只接受能下的 0..8；非法输出有限重试；失败显示原因 + 「让它再试一次」，
  **不偷偷替 TA 下**。

**P0-C 优先级一（0.1.79）**
- `ui/games/GameSession.kt`：局 `id` + `revision` + 请求 `requestId` 认领/校验；旧局、迟到、重复回复丢弃。
- 请求期间禁用棋盘/重开/换 TA/重试（显式防重）。
- 共享 `AppContainer.chatClient` + `UsageTag(purpose = game_tic_tac_toe)`，归属受邀 TA；
  非法动作的自动重试也是独立一次实际调用（各自计入用量）。
- 严格解析：整段（trim 后）必须正好一个能下的 0..8 数字；解释文字/多数字/负数/越界/占用/空全拒。
- `MoveFailure` 类型化失败：只对非法格自动重试；网络/配置/超时不重复付费；45 秒超时、回答 200 字符上限；
  `CancellationException` 原样抛出。

**P0-C 优先级二（0.1.80）**
- 新增 Room 表 `game_sessions`（`data/db/GameSessionEntity.kt` + `GameSessionDao`），DB **44 → 45**（AutoMigration，只加表）。
- `data/GameSessions.kt`：只留最近一局；保存时把「有没有请求在途」记下；
  `GameSessionCodec` 纯函数编解码，损坏数据返回 null 而不是崩。
- 对局页：进页面恢复局面；恢复时在途请求一律清掉 → 显示「上次的棋局被打断了…不会自动重试」+
  「继续 TA 的回合」按钮；TA 被删/存档损坏时明说并只清这一局，不动聊天/日记。

## 2. 关键文件

| 文件 | 作用 |
|---|---|
| `ui/games/TicTacToe.kt` | 纯规则状态机 |
| `ui/games/GameSession.kt` | 局 id + revision + 请求认领/校验 |
| `ui/games/GameAi.kt` | 真模型落子（严格解析、类型化失败、超时、用量标签） |
| `ui/games/TicTacToeScreen.kt` | 对局页（邀请、恢复、防重、继续按钮） |
| `ui/games/GamesHomeScreen.kt` | 小游戏中心 |
| `ui/home/HomeScreen.kt` | 主页「小游戏」卡片 |
| `data/db/GameSessionEntity.kt` | 存档表 + DAO |
| `data/GameSessions.kt` | 存档读写 + 编解码 |
| 测试 | `TicTacToeTest`(9) · `GameSessionTest`(10) · `GameAiTest`(5) · `GameSessionCodecTest`(5) |

## 3. 测试

- 命令：`C:\Users\15536\.dsh\work\build.cmd :app:testDebugUnitTest`（出包：`:app:assembleDebug`）
- 结果：**144 套件 / 1025 用例全绿**；Debug 构建成功。
- 覆盖：双击只一请求、错 requestId/旧 revision/重复回复丢弃、重开使旧请求失效、失败保留棋盘可继续、
  非回合/越界/结束后不可落子、严格解析全部拒绝项、存档往返与损坏拒绝、恢复后在途请求清掉。

## 4. 真机验证（0.1.80-mod / 荣耀 YAL-AL50 / Android 10）

| 场景 | 结果 | 截图（`C:\Users\15536\.dsh\work\dev\`） |
|---|---|---|
| 正常对局：人下中心 X → TA 真模型落 O | ✅ | `p0c_1.png` |
| 杀进程 → 重启 → 回井字棋 | ✅ 完整局面恢复，轮到你（X） | `restore_before.png` / `restore_after.png` |
| 请求中杀进程 → 重启 | ✅ 显示「已中断…不会自动重试」+「继续 TA 的回合」 | `restore_interrupted.png` |
| 点「继续 TA 的回合」 | ✅ 重新请求并落 O | `restore_continue.png` / `restore_continue2.png` |
| 模型回非法格（0.1.76/0.1.77 首测） | ✅ 被拒并提示，不落子 | `p0b_move2.png` 等 |

## 5. 已知限制 / 未测

- **角色删除后的身份**：只做「TA 不在了」提示 + 清当前存档；没有稳定身份映射，ID 复用可能误认（未实现）。
- **备份导入**：棋局不进 ZIP 备份（未接入），恢复/撤销恢复不会迁移旧局。
- **跨进程**：重启后只靠「已中断」标记，不校验旧 requestId（旧请求本来也不会回来）。
- **未逐项真机**：旋转屏幕、断网失败后继续、多 TA 切换、快速连点（逻辑测试覆盖）。
- **数据迁移**：44→45 只加表；安装覆盖后聊天/日记未逐项人工核对（App 启动、聊天页、游戏页正常）。

## 6. 验收路径

1. 安装 `CLEOS-0.1.80-mod.apk`（覆盖安装，聊天记录/日记不丢）。
2. 主页（底栏第 4 个）→「记忆」与「收藏」之间的「小游戏」→ 小游戏中心 → 井字棋。
3. 「选一个 TA」→ 选一个真实角色 → 下 X。
4. 等 TA 用模型回 O（请求失败/超时会提示原因，可手动重试）。
5. 中途杀进程或返回再进：局面还在。
6. 请求进行中杀进程：再进显示「已中断」+「继续 TA 的回合」，不会自己扣费重试。
