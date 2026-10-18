# 五子棋 P1 实现报告

- **当前版本**：`0.1.81-mod`（versionCode 253）；Room DB **46**
- **安装包**：`C:\Users\15536\.dsh\work\dist\CLEOS-0.1.81-mod.apk`（桌面同款）
- **任务**：GitHub Issue #2「五子棋：邀请 TA 对弈、规则、存档与真机验收」
- **真机**：荣耀 YAL-AL50 / Android 10 / 1080×2340，模型「入梦 Flash」

## 状态总览

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1-A | 小游戏中心入口、原生 15×15 棋盘、黑先/白后、五连/平局、重开、邀请真实 TA、首版不禁手 | ✅ 完成（真机） |
| P1-B | 复用共享 chatClient 真模型落子；`row,col` 严格解析；sessionId+revision+requestId；非法输出有限重试；UsageTag `game_gomoku` | ✅ 完成（真机） |
| P1-C | 存档与井字棋隔离（DB 45→46 加 `gameType`）；退出/杀进程恢复；中断标注不自动重试 | ✅ 完成（真机恢复验证） |
| 未做 | 禁手规则、旋转/离线/超时逐项真机、角色删除/ID 复用与备份导入的专项测试 | ❌ 见「已知限制」 |

## 1. 实现

**规则（P1-A）**——`ui/games/Gomoku.kt`（纯 Kotlin）：
- 15×15，黑（1）先手、白（2）后手；横、竖、两条斜线任意方向连续 ≥5 子获胜；满盘平局。
- 落子合法性：越界、已占、非本回合、结束后一律拒绝；记录 `lastRow/lastCol` 供 UI 高亮。
- **首版不使用禁手**（三三/四四/长连按普通胜负），页面明确写着。

**界面（P1-A）**——`ui/games/GomokuScreen.kt`：
- 原生 Compose Canvas 画 15×15 格线与棋子；`detectTapGestures` 换算行列；最近一步画高亮圈。
- 邀请真实 TA 列表、重开、换 TA；等待时整盘禁用（防重复触发）。

**模型（P1-B）**——`ui/games/GomokuAi.kt`：
- 走 `AppContainer.chatClient`（诊断/用量回调都在里面），`UsageTag(purpose = "game_gomoku")` 归属受邀 TA。
- 棋盘压缩成 15 行符号（`.` 空 / `X` 玩家 / `O` TA），坐标规则 `row,col` 0-14。
- 严格解析：整段 trim 后必须正好 `行,列`；越界/已占/多字/负数全拒。
- 只对「非法输出」自动重试（总次数有界 3 次）；网络/配置/超时 60s 不重复付费；取消原样抛出。

**请求归属（P1-B）**——`ui/games/GomokuSession.kt`（纯 Kotlin）：
- 局 id + revision + requestId 认领；重复/迟到/错 revision/旧局回复丢弃；重开使旧请求失效。

**存档（P1-C）**——`data/db/GameSessionEntity.kt` + `data/GameSessions.kt`：
- `game_sessions` 加 `gameType` 列（DB 45→46 AutoMigration，默认值把旧局归为井字棋）。
- **每种游戏各自保留最近一局**（`keepOnly(gameType, id)`），互不覆盖。
- 恢复时在途请求一律清掉（进程重启回不来）→ 标「已中断」，需用户点「继续 TA 的回合」，不自动付费。

## 2. 测试

- 命令：`C:\Users\15536\.dsh\work\build.cmd :app:testDebugUnitTest` / `:app:assembleDebug`
- 结果：**148 套件 / 1055 用例全绿**；Debug 构建成功。
- 新增：`GomokuTest`(12) 横竖双斜五连/四子不算/白胜/边界/越界/非法/满盘平局；`GomokuSessionTest`(10) 防重/迟到/重复/旧局/失败保留；`GomokuAiTest`(4) 严格坐标解析与 prompt；`GomokuCodecTest`(4) 225 格编解码与损坏拒绝。

## 3. 真机验证（0.1.81 / 荣耀 Android 10）

| 场景 | 结果 | 截图（`C:\Users\15536\.dsh\work\dev\`） |
|---|---|---|
| 游戏中心出现「五子棋」入口 | ✅ | `gomoku1.png` |
| 邀请卡片（黑先/禁手说明）→ 选真实 TA | ✅ | `gomoku2.png` |
| 15×15 棋盘、棋子、最后一步高亮 | ✅ | `gomoku4.png` |
| 落黑子 → TA 真模型下白子 | ✅ | `gomoku7.png` |
| 杀进程/切回后棋局恢复（含历史棋子） | ✅ | `gomoku4.png`（恢复后的局面） |

> 过程中手机前台曾被 DSH App 抢占，回到 Cleos 后棋局仍正确恢复；ta 的落子请求在后台完成，符合「离页继续」的语义。

## 4. 已知限制 / 未测

- **禁手**：不做三三/四四/长连禁手判定（UI 已说明）。
- **旋转屏幕**、断网/超时、快速连点的**逐项真机**未测（逻辑测试覆盖）。
- **角色删除 / ID 复用 / 备份导入**：仅沿用井字棋的「TA 不存在就提示并清当前存档」；没有稳定身份映射，备份导入不会迁移旧局（风险与井字棋相同，报告如实列出）。
- 棋局不进入 ZIP 备份。
- 五子棋与井字棋的 `GameAi`/`GomokuAi` 有少量重复（prompt/解析不同，未强行抽象成一个通用引擎）。

## 5. 验收路径

1. 安装 `CLEOS-0.1.81-mod.apk`（覆盖安装）。
2. 主页 → 小游戏 → **五子棋** → 选一个真实 TA。
3. 点棋盘落黑子，等 TA 用模型落白子（等待时显示「TA 在想…」）。
4. 关掉页面/杀进程再进：局面恢复；若当时在等 TA，显示「已中断」+「继续 TA 的回合」。
5. 井字棋存档不受影响（两个游戏各存各的）。
