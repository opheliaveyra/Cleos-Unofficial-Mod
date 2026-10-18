# 小游戏中心 P0：井字棋 实现报告

版本：`0.1.75-mod`（versionCode 247）
任务书：`docs/GAMES_P0_WORK_TASK.md`

## 一、本次完成（P0-A + 本地状态机）

- **主页入口**：主页「记忆」与「收藏」之间新增「小游戏」卡片 → 小游戏中心 → 井字棋。
  **底栏仍是四项**（聊天/日记/待办/主页），没有加第五个。
- **邀请 TA**：从 `Companions.all` 取**真实角色列表**弹窗选择；打开弹窗不发任何请求；
  没有角色时提示先去聊天页建一个。
- **对局**：人执 X 先手、TA 执 O；点空格落子，本地校验（非空/非本回合/已结束都不能下）。
- **TA 落子（P0-A 模拟）**：本地策略——能赢就赢、能堵就堵、否则中心/角/边；
  UI 明确写「模拟对手，还没接模型」，不伪装成模型行动。
- **胜负/平局/重开**：横竖斜三连、九格平局都处理；「重开」「再来一局」可用。

## 二、关键文件

| 文件 | 作用 |
|---|---|
| `ui/games/TicTacToe.kt` | 纯 Kotlin 状态机：棋盘、回合、胜负、合法落子、模拟建议；无 Android 依赖 |
| `ui/games/TicTacToeScreen.kt` | 对局页：邀请弹窗、棋盘、状态、重开 |
| `ui/games/GamesHomeScreen.kt` | 小游戏中心页（目前只有井字棋） |
| `ui/home/HomeScreen.kt` | 新增 `GamesHomeCard`，插在记忆与收藏之间 |
| `ui/MainScreen.kt` / `ui/CleosNavHost.kt` | 传 `onOpenGames`，注册 `GamesRoute` / `TicTacToeRoute` |
| `app/src/test/.../ui/games/TicTacToeTest.kt` | 9 个测试 |

## 三、测试结果

- `:app:testDebugUnitTest`：**141 套件 / 1005 用例全绿**（新增 9 个井字棋规则测试：
  先手与轮换、重复落子、横/斜三连、平局、结束后不能下、赢优先于堵、能堵时去堵、重开清空）。
- `:app:assembleDebug`：BUILD SUCCESSFUL。
- **真机验证：已完成**（荣耀 YAL-AL50 / Android 10 / 1080x2340，0.1.75-mod）。

  实测流程与结果（ADB + uiautomator + 截图）：

  1. 主页「记忆」与「收藏」之间能看到「小游戏」卡片，底栏仍是四项 ✓
  2. 点开 → 「小游戏中心」→ 井字棋 ✓
  3. 点「选一个 TA」→ 弹窗列出**真实 TA**（此机只有 1 个「TA」）→ 选中即开局 ✓
  4. 对局页：顶部显示 TA 名与「你 = X（先手）· TA = O（模拟对手，还没接模型）」，
     状态「轮到你（X）」；点中心格 → 出现 X；约 0.5 秒后 TA 在左上角落 O；状态回到「轮到你（X）」✓
  5. 继续下两步后 TA 在左列连成三格 → 状态显示「TA 赢了」，并出现「再来一局」✓
  6. 棋盘渲染、按钮可点、返回正常；未见崩溃（logcat 无异常）。

  截图：`dev/game2.png`（主页卡片）、`game3.png`（小游戏中心）、`game4.png`（未邀请）、
  `game5.png`（邀请弹窗）、`game6.png`（开局）、`game7.png`（落子后）、`game8.png`（TA 赢）。

  未覆盖：退出后恢复（本来就没做持久化）、红米/其他机型、连点防重、邀请多个 TA 时切换。

## 四、未完成 / 明确标注

- **P0-B（真实模型落子）未做**：现在 TA 的落子是本地模拟，没有调用 Provider。
  接入时需要：把局面转成紧凑请求、只接受 0..8 的结构化动作、校验 session/revision、
  失败重试与防重——这些都要在 `TicTacToe` 之外另写一层（目前没有）。
- **P0-C 的持久化恢复未做**：退出页面/重启后对局不保留（内存态）；没有新增 DB 表、没有迁移。
- 没有「TA 接受/拒绝邀请」的对话；邀请确认即开始，UI 如实表述为本地开局。


---

## 0.1.76 – 0.1.78：P0-B（TA 真的用模型落子）

### 实现

- `ui/games/GameAi.kt`：
  - 通过 `AppContainer.registry.route(Feature.Chat)` 拿 TA 当前模型（`ModelRoutes` 三层结构），
    `secrets.key(baseUrl)` 取 Key，用 `ChatClient.stream` 发一轮；
  - 局面压成一句：3×3 摆出来 + **明确列出可选数字**（`你只能从这些数字里选一个落子：…`）；
  - `parseMove`：只接受 0..8 里**现在能下**的格子，别的数字（越界/已占）一律拒绝；
  - **自动重试**：只对「模型回了非法格」重试（最多 3 次）；网络/未配置直接返回，不重试。
- `TicTacToeScreen`：人落子后调用 `GameAi.askMove`；失败时显示原因 + 「让它再试一次」，
  **不偷偷替 TA 下**；卡片文案改为「TA = O（会真的调模型来下）」。

### 真机实测（荣耀 YAL-AL50 / Android 10，模型：入梦 Flash）

- 人下中心 X → 约几秒后 TA 落左上 O（合法）✓；状态在「TA 在想…」与「轮到你（X）」间正确切换；
- 0.1.76 首测：模型回了**被占的格子**（1、4）→ 被拒并显示「TA 回的不是 0-8 的合法格子：4」+ 重试入口 ✓
  （这是设计行为：非法输出不落子、不假装是模型下的）；
- 0.1.77：prompt 补「空格列表」，仍偶发非法 → 0.1.78 加**自动重试**（最多 3 次）后，
  实测一次落子成功（左上 O）✓；
- 测试中 USB 掉线数次（设备 offline），`adb kill-server` + `start-server` 可恢复。

### 仍未做（如实）

- **对局持久化**（退出/重启恢复）：没有新增 DB 表（P0-C 未做）；
- **防并发双步**：邀请弹窗/落子期间没有 sessionId + revision 校验（单机单人低风险，但任务书要求）；
- 迟到响应丢弃：当前实现是「请求期间棋盘锁（thinking）」，没有 revision 级别校验；
- 「TA 接受/拒绝邀请」的对话：没有。

---

## 0.1.79-mod：P0-C 优先级一（正确性）已做；持久化未做

### 已完成（优先级一）

1. **请求归属校验**：新增 `ui/games/GameSession.kt`（纯 Kotlin 状态机）——
   每局唯一 `id`、每次落子 `revision + 1`、每次请求 `requestId`；`resolveTa(requestId, move)` 只在
   requestId + revision 都对得上且格子能下时才落子。页面在回复回来时再核对 `session.id`，
   **旧局的回复直接丢弃**。
2. **防重**：`claimTaMove` 原子认领（已有待处理请求 → null）；UI 在请求期间禁用棋盘/重开/换 TA/重试；
   双击、重入不会发两次请求。重试按钮 `enabled = !busy`。
3. **共享客户端 + 用量**：`GameAi` 不再自建 `OkHttpClient`，改走 `AppContainer.chatClient`；
   每次调用带 `UsageTag(scope = SCOPE_TA, scopeId = 受邀 TA, purpose = "game_tic_tac_toe")`。
   非法动作的自动重试也是独立一次实际调用（各自计入用量）。
4. **角色身份**：`askMove` 用 `companions.get(taId)` 取真实角色，名字（+ 设定前 300 字）进 prompt；
   角色被删 → `MoveFailure.TaMissing` 明确提示。仍沿用统一 `Feature.Chat` 路由，界面如实写「会真的调模型来下」。
5. **严格解析**：`parseMove` 只接受整段（trim 后）恰好一个能下的 0..8 数字；
   解释文字、多数字、负数、越界、占用、空输出全部拒绝（原「从文字里扫数字」的测试已改写）。
6. **失败类型化**：`MoveFailure` 枚举（NotConfigured / NoKey / TaMissing / Network / Timeout / IllegalMove / NotClaimed）；
   自动重试**只对 IllegalMove**，网络/配置/超时直接返回，不重复付费；`attempts` 是总调用次数；
   45 秒超时、回答长度上限 200 字符；`CancellationException` 原样抛出。
7. 清理过时注释/未用 import（不再有「P0-A 本地模拟」字样）。

### 测试

- 新增 `GameSessionTest` 10 个：双击只一请求、错 requestId 丢弃、旧 revision 丢弃、重复回复无效、
  重开使旧请求失效、失败保留棋盘可继续、非回合/越界/结束后不可落子。
- `GameAiTest` 改写为严格解析：单数字接受；解释文字/多数字/「3。」/负号/越界/占用/空全部拒绝；
  prompt 带 TA 名字与设定。
- 全量 **143 套件 / 1020 用例全绿**；`:app:assembleDebug` 成功。

### 真机（荣耀 YAL-AL50 / Android 10 / 入梦 Flash，0.1.79-mod）

- 安装 → 主页 › 小游戏 › 井字棋 → 邀请 TA → 人下中心 X → 数秒后 TA 用真实模型落左上 O ✓
- 截图：`dev/p0c_1.png`。

### 明确未做（优先级二）

- **棋局持久化**：没有新增 Room 表/迁移；退出页面或杀进程后棋局不保留（当前只在内存）。
  因此「杀进程恢复 O 回合、不自动重试付费」这项**未实现也未验证**。
- session/revision 校验只在**页面内**有效；跨进程无状态可校验。
- 角色删除/备份恢复后的身份处理：仅做了「TA 不存在」提示，没有稳定身份映射与旧局迁移。
- 「请求中重开」当前的做法是**禁用重开按钮**（比让旧请求失效更保守）；任务书建议的「先失效再新建」
  等价效果已由 `session.id` 校验保证。
## 五、验收路径（设备接上后）

1. 装 0.1.75 → 主页（第 4 个底栏）→ 看到「小游戏」卡片；
2. 点开 → 小游戏中心 → 井字棋；
3. 点「选一个 TA」→ 弹出真实角色列表 → 选一个；
4. 点空格落子（X），约 0.5 秒后 TA 下 O；连成三格看胜负提示；
5. 点「重开」清空；返回键回小游戏中心/主页。
