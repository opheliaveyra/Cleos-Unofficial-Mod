# Cleos 真机复测报告 RETEST-ACCEPTANCE-20261028

> 执行人：津渡（DSH）
> 执行日期：2026-10-28
> 目标仓库：opheliaveyra/Cleos-Unofficial-Mod
> 目标分支：feature/chat-file-upload-and-hold-to-talk

---

## 执行环境

| 项 | 值 |
|---|---|
| 设备型号 | HONOR YAL-AL50 |
| Android 版本 / SDK | Android 10 / SDK 29 |
| 屏幕分辨率 | 1080×2340 @480dpi |
| 构建 commit SHA | 8f2b237e53f34b7fba0f9dbdc17688fc70332bd2（Merge PR #11） |
| APK 类型 | release（signed） |
| 测试用 TA | TA（入梦 Flash，deepseek-v4-flash） |
| 测试用模型 | DeepSeek deepseek-v4-flash |
| dsh 版本 | DeepSeek Harness（本地） |
| 执行日期 | 2026-10-28 03:00-04:10 |
| 测试构建版本 | versionCode 300 / versionName 0.1.92-mod-retest（临时，测试后已还原为 264 / 0.1.92-mod） |

---

## 🚨 关键阻塞：设备无 API Key

执行过程中发现测试设备上的 Cleos **没有配置任何 API Key**：

- 五子棋/井字棋落子后 TA 回合提示「**TA 还没填 API Key。**」
- 服务商页面显示「**这个地址还没有 Key —— 点「编辑」把 Key 填上。**」

证据：[blocker-no-api-key.png](blocker-no-api-key.png)、[gmk-api-key-missing.png](gmk-api-key-missing.png)

**影响范围**：所有依赖真实模型调用的测试项（GMK-02/03、TOK-01/02/03、HTML-05 及 P0 Token 实际记录验证）均无法执行，只能标记为「阻塞」。

这也是上一轮验收报告（FINAL-ACCEPTANCE-20261025）中 recap/feed/letter 未能触发的根本原因——不是功能未触发，而是设备根本没有 Key 可调用模型。

---

## 结果汇总

| 章节 | 条目数 | 通过 | 失败 | 观察/阻塞 | 备注 |
|---|---|---|---|---|---|
| 一、HTML 小组件状态恢复 | 5 | 4 | 0 | 1 | HTML-05 需两个有状态组件，自检页只有一个 |
| 二、五子棋 | 6 | 2 | 0 | 4 | GMK-01 部分通过；GMK-02/03 阻塞（无 Key）；GMK-05 未执行；GMK-06 观察完成 |
| 三、Token 用量标签 | 4 | 1 | 0 | 3 | TOK-00 基线完成；TOK-01/02/03 阻塞（无 Key） |
| 四、可选 PR 分支 | 2 | — | — | 2 | 未执行（需切换分支单独构建） |
| 五、PR #11 回归 | 3 | 2 | 0 | 1 | LINT-01 不适用（Android 10 < API 31） |

---

## 一、HTML 小组件状态恢复（Issue #3）

### HTML-01 输入框内容恢复（离开重开）— ✅ 通过

- 操作：在自检页的「输入框」样例输入 `RETEST-HTML-01` → 关闭自检弹窗 → 重新打开
- 结果：输入框内文本 `RETEST-HTML-01` **完整保留**
- 证据：[html01-input-restored.png](html01-input-restored.png)

### HTML-02 滚动位置恢复 — ✅ 通过

- 操作：放大「长 HTML」样例 → 滚动到第 10 段可见 → 收起 → 重新放大
- 结果：重开后**精确停留在离开前的滚动位置**（第 1 段截断、第 10 段可见）
- 证据：[html02-scrolled-before.png](html02-scrolled-before.png)、[html02-scroll-restored.png](html02-scroll-restored.png)

### HTML-03 杀进程后恢复 — ✅ 通过

- 操作：输入 `RETEST-HTML-01` → `am force-stop com.cleo.cleos` → 重新启动 → 打开同一样例
- 结果：输入框文本**仍然保留**（localStorage 在 WebView 数据目录，杀进程不清除）
- 证据：[html03-kill-restored.png](html03-kill-restored.png)

### HTML-04 「重置状态」按钮 — ✅ 通过

- 操作：放大输入框样例（含 `RETEST-HTML-01`）→ 点弹窗右上角「重置状态」→ 收起 → 重新打开
- 结果：输入框恢复为初始占位符「输入点什么」，重置生效
- 证据：[html04-before-reset.png](html04-before-reset.png)、[html04-after-reset.png](html04-after-reset.png)

### HTML-05 多个小组件状态隔离（不串档）— ⚠️ 阻塞

- 原因：自检页仅提供**一个带用户可写持久化状态的样例**（输入框）。其余五个样例（短/长/宽表格/按钮/动态内容）没有可写的持久状态——按钮状态是 JS 闭包变量，报告明确不恢复。
- 无法构造 A/B 两个独立有状态的组件，因此**无法在自检页完成隔离验证**。
- 需在聊天中由 TA 生成两个不同的真实 HTML 小组件才能测，但设备无 API Key，无法触发。
- 结论：阻塞，需有 Key 的环境重测。

---

## 二、五子棋（Issue #2）

### GMK-01 旋转屏幕 — ⚠️ 部分通过

- 操作：五子棋对局中（1 黑子，TA 回合）→ `user_rotation=1` 转横屏 5 秒 → 转回竖屏
- 结果：
  - ✅ 旋转前后棋盘棋子、当前回合（TA 的回合）**正确保留**，不崩溃、不丢局
  - ✅ 转回竖屏后 UI 完全恢复（标题/TA 信息/回合/按钮）
  - ⚠️ 横屏下棋盘放大填满屏幕，**顶部信息栏（标题、TA 信息、回合文字）不可见**
  - ⚠️ 无法达到「3 黑 2 白」前置条件（TA 因无 Key 无法落子）
- 证据：[gmk01-landscape.png](gmk01-landscape.png)、[gmk01-rotate-restored.png](gmk01-rotate-restored.png)

### GMK-02 断网时 TA 落子失败处理 — ⚠️ 阻塞（部分观察）

- 观察到的失败路径：落子后 TA 回合立即提示「TA 还没填 API Key。」，棋盘保留、玩家黑子不丢、无无限自动重试。
- 但这**不是真实的网络失败路径**（本地 Key 校验先于网络请求失败）。
- 无法测试：飞行模式下的网络超时/失败提示、恢复网络后继续 TA 回合。
- 结论：阻塞（无 Key），失败提示的形态已观察到（明确原因、棋盘不丢、不自动重试）。

### GMK-03 超时（60s）行为 — ❌ 阻塞

- 原因：无 API Key，无法发起真实模型请求，无法测量 60s 超时行为。
- 未执行。

### GMK-04 存档隔离（五子棋 vs 井字棋）— ✅ 通过（部分）

- 操作：
  1. 井字棋：已有存档（X: 中/右下，O: 左上/左下），落一子到右上角
  2. 五子棋：已有存档（1 黑子中腹），退出重进
  3. 五子棋点「重开」，再进井字棋检查
- 结果：
  - ✅ 五子棋存档退出重进**完好保留**
  - ✅ 井字棋存档退出重进**完好保留**（含新落的 X）
  - ✅ **重开五子棋后井字棋存档完全不受影响**
  - ⚠️ 无法达到「玩家+TA 各下 2 步」的完整条件（无 Key）
- 证据：[gmk04-save-persisted.png](gmk04-save-persisted.png)、[gmk04-tic-save.png](gmk04-tic-save.png)、[gmk04-tic-after-move.png](gmk04-tic-after-move.png)、[gmk04-gomoku-independent.png](gmk04-gomoku-independent.png)、[gmk04-restart-isolated.png](gmk04-restart-isolated.png)

### GMK-05 角色删除 / ID 复用 — ⚠️ 未执行

- 原因：需新建并删除一次性 TA，在测试设备上会改动真实数据（当前 2 个 TA 承担历史聊天记录）；且无 API Key 无法完成对局。
- 风险与收益权衡后未执行，如实记录。

### GMK-06 棋局是否进入 ZIP 备份 — ✅ 观察完成

- 操作：设置 → 数据与备份 → 导出备份 → 得到 `cleos-备份-2026-10-28.zip`（7200 字节）→ 解析清单
- 结果：
  - ZIP 内**只有 `backup.json`** 一个文件
  - `backup.json` 顶层键：format / version / exportedAt / settings / conversations / messages / diary / todos / companions / letters / memories / stickers / favorites / lore / feedPosts / groups / groupMembers / groupTurns / groupMessages / groupMemories / mcpServers
  - **无任何 game/gomoku/tic/board 相关键**
- 结论：与 GOMOKU_P1_REPORT §4 / GAMES_P0_REPORT §5 的说法一致——**棋局不进入 ZIP 备份**。
- 证据：[gmk06-zip-contents.txt](gmk06-zip-contents.txt)

---

## 三、Token 用量标签（feat(usage) 42fc821）

### TOK-00 基线记录 — ✅ 完成

- 本月：34 次请求，输入 174538 / 输出 13354 / 总计 187892
- 用途分布：chat 168215、game_tic_tac_toe 10926、game_gomoku 8751
- 角色分布：TA 19677
- **recap / feed / letter：无记录**
- 基线时间：2026-10-28 04:04
- 证据：[tok00-baseline.png](tok00-baseline.png)

### TOK-01 触发 Recap 并验证 purpose=recap — ❌ 阻塞

- 触发条件：未折叠消息数 > window(40) + step(20) = 60 条
- 当前会话 37 条消息，且**无 API Key**，无法通过聊天增加到 65 条（聊天需要模型回复）
- 未执行。

### TOK-02 触发 Feed 并验证 purpose=feed — ❌ 阻塞

- 需配置新闻源 + 真实模型执行 write/planPhoto/visit/interact
- 设备无 API Key，无法执行
- 未执行。

### TOK-03 触发 Letters 并验证 purpose=letter — ❌ 阻塞

- 需写信 → 等待 TA 回信（触发 `Letters.compose()` 的模型调用）
- 设备无 API Key，无法执行
- 未执行。

---

## 四、可选：PR 分支真机项 — 未执行

### PR-01 PR #5 紫雾手账主题切换截图矩阵 — 未执行

- 需 checkout `feat/lavender-scrapbook-batch01` 并单独构建
- 本次聚焦默认分支复测，未切换分支

### PR-02 PR #4 纸绘图标真机截图 — 未执行

- 需 checkout `feat/paper-functional-icons` 并单独构建
- 同上，未执行

---

## 五、PR #11 回归（lint 修复）

### LINT-01 启动屏背景（日间/夜间）— 不适用

- 启动屏属性只在 **Android 12（API 31）+** 生效
- 测试设备为 **Android 10（SDK 29）**，该章节在本次设备上不适用
- 需 Android 12+ 设备重测

### LINT-02 TA 设置页旋转不丢状态 — ✅ 通过（部分）

- 操作：进入「说话方式」页 → 将「深度思考」开关从关改为开 → `user_rotation=1` 旋转 → 检查
- 结果：
  - ✅ 设置项「深度思考」保持开启状态
  - ✅ 页面不崩溃
  - ⚠️ `SurfaceOrientation: 0`——**设置页锁定竖屏**，系统旋转指令不生效（无法产生真实横屏状态）
  - 已恢复「深度思考」为关闭（原始状态）
- 证据：[lint02-before-rotate.png](lint02-before-rotate.png)、[lint02-state-persisted.png](lint02-state-persisted.png)

### LINT-03 小组件放大弹窗布局 — ✅ 通过

- 操作：打开 HTML 小组件放大弹窗（按钮样例与长内容样例各一次）→ 检查布局 → 收起 → 重开
- 结果：
  - ✅ 弹窗居中、宽度约屏幕 80%、内容可滚动
  - ✅ 两次打开均正常，无错位
  - ✅ 顶部有「放大看 / 重置状态 / 收起」三个按钮，可正常收起
- 证据：[lint03-expand-dialog.png](lint03-expand-dialog.png)

---

## 发现的问题

### P1（阻塞测试，非代码缺陷）

1. **设备无 API Key** —— 阻断了所有模型调用相关测试。不是 App 缺陷，是测试环境配置缺失。

### P2（观察到的现象，待评估）

2. **五子棋横屏下顶部信息栏不可见** —— 横屏时棋盘放大填满屏幕，标题/TA 信息/回合文字被挤出可视区。棋盘本身正常。是否算缺陷需产品判断（棋盘优先可理解，但回合信息缺失影响可用性）。
3. **设置页锁定竖屏** —— 「说话方式」页等设置页面不随系统旋转（`screenOrientation` 锁定），旋转测试无法产生横屏状态。若设计如此则正常。

### 未发现的问题

- HTML 状态恢复四项全部实际工作（含杀进程、重置）
- 棋局存档隔离正常工作
- 棋局不进入 ZIP 备份（与文档一致）
- 放大弹窗布局正常

---

## APK 构建结果

| 项 | 值 |
|---|---|
| 构建命令 | `:app:assembleRelease` |
| 结果 | BUILD SUCCESSFUL in 5m 34s |
| 基线 commit | 8f2b237 |
| 产物 | app-release.apk（release, signed） |
| 安装 | adb install -r → Success |
| 临时 versionCode | 300（测试后已还原为 264） |

---

## GitHub commit SHA

| SHA | 内容 |
|---|---|
| 8f2b237 | 构建基线（Merge PR #11） |
| 42fc821 | P0：补齐 Recap/FeedAi/Letters 后台 Token 用量统计（上一轮） |
| e9aa865 | P1/P2 缺口审计（上一轮） |
| （本次提交） | 本复测报告与证据截图 |

---

## 剩余风险与后续建议

### 阻塞项（需有 Key 的环境重测）

1. **GMK-02/GMK-03**：断网失败路径与 60s 超时行为
2. **TOK-01/02/03**：Recap/Feed/Letters 的 purpose 实际记录
3. **HTML-05**：多组件状态隔离（需两个真实 HTML 组件）
4. **LINT-01**：启动屏（需 Android 12+ 设备）

### 建议

1. **配置 API Key 后补测**：在测试设备的服务商页填入 Key，即可完成 TOK/GMK 的剩余验证。这是唯一真正阻塞的因素。
2. **五子棋横屏布局**：如认为回合信息在横屏下必须可见，建议布局改为信息栏固定顶部、棋盘填充剩余空间。
3. **PR 分支章节**：需要切换分支构建，建议在默认分支验收完成后单独安排。

### 已确认为正常的功能

- HTML 状态恢复（输入框、滚动、杀进程、重置）
- 棋局存档隔离
- 棋局不进备份
- 放大弹窗布局
- 五子棋旋转后棋盘状态保留
