# Cleos 真机复测报告 · 补充（API Key 配置后）

> 执行人：津渡（DSH）
> 执行日期：2026-10-28
> 前置报告：RETEST-ACCEPTANCE-20261028.md（无 Key 阶段）
> 构建基线：8f2b237

---

## 一、API Key 配置后环境变化

原报告执行时设备无 API Key，导致多项阻塞。之后设备配置发生变化：

| 项 | 无 Key 阶段 | Key 配置后 |
|---|---|---|
| 服务商 | DeepSeek（1 家，无 Key） | DeepSeek（无 Key）+ **肖恩**（有 Key） |
| 模型配置 | 1 条（deepseek-v4-flash · DeepSeek） | 2 条（+ **入梦 Flash · 肖恩**） |
| 当前聊天模型 | deepseek-v4-flash · DeepSeek | 改为 **入梦 Flash · 肖恩** |
| TA 可用性 | 所有调用失败「TA 还没填 API Key」 | **正常应答** |

**验证证据**：
- [key-verified-ta-replied.png](key-verified-ta-replied.png)：TA 正常回复（第一轮）
- [key-restored-ta-works.png](key-restored-ta-works.png)：TA 正常回复（恢复配置后复核）

**重要发现：Key 按服务商地址绑定。** 服务商页明确说明「Key 按地址存，换型号不用重填」。改变接口地址等同于换了一家新服务商，原地址下的 Key 不会被复用，表现为「这个地址还没有 Key」。

---

## 二、Key 配置后新增通过的项

### GMK-02 断网时 TA 落子失败处理 — 完整通过

此前仅观察到「无 Key」的失败路径，本次用飞行模式测试了真实网络失败：

| 步骤 | 实测结果 |
|---|---|
| 开飞行模式后落子 | 黑子落盘成功 |
| TA 回合失败提示 | 「找不到服务器：检查地址，或者网络是不是断了」 |
| 棋盘/玩家棋子 | 黑子完整保留，不丢 |
| 是否无限重试 | 无自动重试，只出现一次失败 + 手动「让它再试一次」 |
| 恢复网络后手动重试 | TA 成功落白子，回合切换为「轮到你（黑）」 |
| 是否重复落子 | 只落一子，无重复请求 |

证据：[gmk02-airplane-on.png](gmk02-airplane-on.png)、[gmk02-network-fail.png](gmk02-network-fail.png)、[gmk02-retry-success.png](gmk02-retry-success.png)

### TOK Token 统计链路 — 实测确认工作

基线（TOK-00）：34 次请求 / 输入 174538 / 输出 13354 / 总计 187892
一次真实模型调用后：**35 次请求 / 输入 182139 / 输出 13417 / 总计 195556**

| purpose | 基线 | 调用后 | 增量 |
|---|---|---|---|
| chat | 168215 | **175879** | **+7664** |
| game_tic_tac_toe | 10926 | 10926 | 0 |
| game_gomoku | 8751 | 8751 | 0 |
| recap / feed / letter | 无 | 无 | — |

「最近请求」新增：**10-28 04:13 入梦 Flash chat 7601+63**

结论：**usageTag → TokenUsageEntity → UsagePage 整条链路工作正常**，新调用被正确记录（scope=TA、purpose=chat、模型=入梦 Flash、输入/输出 token 为实际值）。这直接验证了 P0 所依赖的统计机制本体。

证据：[tok00-baseline.png](tok00-baseline.png)、[tok-chat-record-verified.png](tok-chat-record-verified.png)

### GMK-03 超时（60s）行为 — 无法用改地址方式测试（发现设计事实）

原计划将模型地址改为不可达地址 (http://192.0.2.1) 以触发 60s 超时。实测：

- 改地址并保存后，落子立即提示「**TA 还没填 API Key**」——Key 与地址绑定，新地址下没有 Key。
- **本地 Key 校验先于网络请求**，请求根本没有发出，无法进入 60s 超时路径。

这解释了无 Key 阶段的同类现象：不是网络路径问题，而是本地 Key 校验失败。

**结论**：60 秒超时行为在当前条件下无法测试。要测需要保持地址与 Key 不变、但让该地址不可达（hosts 劫持 / 路由器屏蔽 / 代理拦截），当前无 root 且无法改设备网络策略。

已完整恢复原地址 https://speed31.toter.me 并复核 TA 可用。证据：[gmk03-address-changed.png](gmk03-address-changed.png)、[gmk03-key-bound-to-address.png](gmk03-key-bound-to-address.png)、[gmk03-address-restored.png](gmk03-address-restored.png)

---

## 三、仍未完成的项（如实说明）

| 项 | 状态 | 原因 |
|---|---|---|
| TOK-01 Recap | 未执行 | 需会话消息 > 60 条。当前新会话 4 条，需约 28 轮对话（约 56 次模型调用）。成本与时长可接受，建议单独执行。 |
| TOK-02 Feed | 未执行 | 需先配置外部新闻源（Feed 设置），环境未配置。 |
| TOK-03 Letters | 未执行 | 需写信后等待 TA 回信（回信时机由策略决定，可能数小时至 24 小时）。 |
| HTML-05 多组件隔离 | 未执行 | 需聊天中由 TA 生成两个不同 HTML 小组件；自检页只有一个带输入框的样例。 |
| GMK-05 角色删除/ID 复用 | 未执行 | 观察项，需新建并删除一次性 TA，会改动设备真实数据，风险较高未执行。 |
| LINT-01 启动屏 | 不适用 | 设备 Android 10（SDK 29），启动屏属性需 API 31+。 |
| PR-01 / PR-02 | 未执行 | 需切换分支单独构建，属可选项。 |

---

## 四、本轮总结

**Key 配置后，最关键的阻塞解除**：

- 已验证 TA 正常调用模型
- 已验证 Token 统计链路端到端工作（P0 机制本体）
- GMK-02 断网失败处理完整通过（此前仅部分）
- 澄清「修改地址 ≠ 模拟网络超时」这一设计事实

**仍待补测**：TOK-01/02/03（recap/feed/letter 实际用量记录）、HTML-05、GMK-03（需网络层手段）。

---

## 五、证据清单（本轮新增）

| 文件 | 内容 |
|---|---|
| key-verified-ta-replied.png | Key 生效后 TA 首次正常回复 |
| key-restored-ta-works.png | 恢复地址后复核 TA 可用 |
| tok-chat-record-verified.png | 用量页：35 次请求，chat 增量 7664 |
| gmk02-airplane-on.png | 飞行模式开启、新局待落子 |
| gmk02-network-fail.png | 断网失败提示「找不到服务器」 |
| gmk02-retry-success.png | 恢复网络后重试成功、TA 落白子 |
| gmk03-address-changed.png | 服务商地址改为不可达地址 |
| gmk03-key-bound-to-address.png | 改地址后立即报「TA 还没填 API Key」 |
| gmk03-address-restored.png | 地址已恢复原值 |

---

## 六、TOK-01 Recap 触发尝试记录（未成功）

尝试用 adb 批量发送短消息把会话推到 60+ 条以触发 Recap 折叠：

**方法**：脚本循环 5 轮，每轮 点输入框 → input text → 点发送 → 等 14 秒回复

**实际结果**：
- 输入法（讯飞）把 input text 发送的 "msg1"~"msg5" 转换为 **"Vmsg"**（首字母被当作拼音碎片）
- 键盘弹出时输入栏位置上移，固定的发送按钮坐标 (930,1335) 在部分轮次落空
- 5 轮中**仅 3 轮成功发送**，会话消息数从 4 条增至约 10 条
- 距离触发阈值（60 条）仍差约 50 条，按此成功率需 80+ 轮尝试

**结论**：TOK-01 **未执行完成**。真机上通过 adb 批量对话不可靠（输入法干扰 + UI 坐标漂移）。要完成需要：
- 人工对话 28+ 轮，或
- 编写基于 uiautomator dump 的健壮 UI 自动化脚本（每轮重新定位控件），或
- 在测试专用会话中预置历史消息（不推荐，等同构造数据）

证据：[tok01-batch-attempt.png](tok01-batch-attempt.png)

---

## 七、TOK-01 / TOK-03 验证通过（玖玖协助对话后）

玖玖在设备上手动对话，会话消息数越过 Recap 折叠阈值，同时操作了信件功能。用量页实测结果：

### 用量总览

| 项 | 值 |
|---|---|
| 总请求 | **56 次**（基线 34 次） |
| 输入 tokens | 351853 |
| 输出 tokens | 15449 |
| 总计 | 367302 |
| 服务商未返回用量 | 1 次（标「未提供」，未用 0 冒充） |

### 按用途分布（含新增）

| purpose | tokens | 状态 |
|---|---|---|
| chat | 342559 | 正常 |
| game_tic_tac_toe | 10926 | 正常 |
| game_gomoku | 9498 | 正常（本轮五子棋测试增量） |
| **recap** | **2177** | **本轮新增，P0 验证通过** |
| **letter** | **2142** | **本轮新增，P0 验证通过** |
| feed | 无 | 仍未触发 |

### 最近请求明细（关键行）

```
10-28 04:30  入梦 Flash  recap   1600+577   ← Recap.kt usageTag 生效
10-28 04:27  入梦 Flash  letter  1941+201   ← Letters.kt usageTag 生效
```

### 结论

- **TOK-01（purpose=recap）— 通过**：用量页出现 recap 记录，归属 TA，模型为入梦 Flash，token 为实际值 1600+577。
- **TOK-03（purpose=letter）— 通过**：用量页出现 letter 记录，归属 TA，模型为入梦 Flash，token 为实际值 1941+201。
- 附带验证：「服务商没返回 usage 标「未提供」，不会拿 0 冒充」的设计在实际数据中出现了 1 次，符合预期。

**P0 三个目标中，recap 与 letter 已完成实测验证。仅剩 feed 未验证。**

证据：[tok01-tok03-recap-letter-verified.png](tok01-tok03-recap-letter-verified.png)、[tok01-tok03-recent-requests-detail.png](tok01-tok03-recent-requests-detail.png)

---

## 八、TOK-02 验证通过 — P0 三个目标全部完成

### Feed 触发过程

1. 聊天页右上角「动态」图标 → 进入「朋友圈」页面
2. 右上角「...」菜单 →「让 TA 逛逛」
3. 弹出「让 TA 说点日常」→ 选择 TA → 点「去逛逛」
4. TA 成功发出一条动态（10月28日 04:34）：

> 凌晨四点半的广场格外安静，风里带着点微凉。忽然有点想念那种没有闹钟、没有待办事项的清晨，睡到自然醒再喝杯热豆浆的感觉。你们今天起得早吗？

### 用量页实测（57 次请求）

| purpose | tokens | 状态 |
|---|---|---|
| chat | 342559 | 正常 |
| game_tic_tac_toe | 10926 | 正常 |
| game_gomoku | 9498 | 正常 |
| **recap** | **2177** | ✅ 通过 |
| **letter** | **2142** | ✅ 通过 |
| **feed** | **781** | ✅ **本轮新增，通过** |
| TA（按角色汇总） | 20424 | 正常 |

最近请求新增：**10-28 04:34 入梦 Flash feed 720+61**

### P0 最终结论

| P0 目标 | purpose | 实测记录 | 结论 |
|---|---|---|---|
| Recap.kt usageTag | recap | 2177 tokens（1600+577） | ✅ 通过 |
| Letters.kt usageTag | letter | 2142 tokens（1941+201） | ✅ 通过 |
| FeedAi.kt usageTag | feed | 781 tokens（720+61） | ✅ 通过 |

**P0 三处后台 Token 用量统计全部完成真机验证。** 每条记录的 scope=TA、模型名、输入/输出 tokens 均为实际值，由服务商返回，未出现伪造或 0 冒充。

证据：[tok02-feed-post-created.png](tok02-feed-post-created.png)、[tok02-feed-verified-final.png](tok02-feed-verified-final.png)

---

## 九、测试 TA 命名（便于区分与引用）

复测期间设备上有 3 个 TA，名字原本都是「TA」，无法区分。经玖玖同意后统一改名：

| 位置 | 名称 | 头像 | 状态/用途 |
|---|---|---|---|
| 第 1 个 | **Mira** | M | 主测试 TA：60 条消息，recap / letter / feed 三项 P0 验证均在此 TA 上完成 |
| 第 2 个 | **Nova** | N | 备用测试 TA |
| 第 3 个 | **Echo** | E | 新建测试 TA（10月28日创建，19 条消息） |

改名方式：主页选中 TA → 设置 → 资料与性格 → 名字。名字在全局同步更新（信、记忆、用量统计的角色行等）。

**对报告的影响**：本报告前文用量统计中的「TA」行（19677 / 20424 tokens）即为 **Mira** 的用量；改名后用量页会显示新名字。

> 注：`input text` 通过讯飞输入法输入中文会被吞掉（"小满"实际未写入），最终改用 AOSP LatinIME 输入英文名，已验证成功。

证据：[retest-ta-naming.png](retest-ta-naming.png)

---

## 十、补充验证（Key 生效后第二轮）

### GMK-01 旋转屏幕 — 完整通过

前一轮因无 Key 无法达到「3黑2白」前置条件。本轮 Key 生效后重新测试：

| 步骤 | 结果 |
|---|---|
| 对局至 3黑3白 | 通过（超过前置要求） |
| 转横屏（SurfaceOrientation=1） | 棋子 3黑3白全部保留、最后一步高亮正确、当前回合正确 |
| 横屏布局 | 棋盘放大填满屏幕；**顶部信息栏（标题/TA名/回合文字）不可见** — 观察项 |
| 转回竖屏 | 标题、Mira、回合、棋子、按钮全部恢复，不崩溃不丢局 |
| 旋转后继续对局 | 正常：新落黑子 + TA 落白子，回合正确切换（4黑4白） |

证据：[gmk01-full-before-rotate.png](gmk01-full-before-rotate.png)、[gmk01-full-landscape-3b3w.png](gmk01-full-landscape-3b3w.png)、[gmk01-full-portrait-restored.png](gmk01-full-portrait-restored.png)

**补充说明**：旋转需先 `accelerometer_rotation=0` 再设 `user_rotation`，否则传感器会覆盖手动方向。

### HTML-05 多组件状态隔离 — 部分通过

**前置**：设置 → 能做的事 →「对话里的小玩意儿」（ToolGroup.Live）默认关闭，需手动开启。开启后 TA 会用 ```html 代码块在回复里输出可直接运行的小组件。

| 步骤 | 结果 |
|---|---|
| 让 TA 生成带输入框的 HTML 小组件 | 通过：TA 正确理解并输出 ```html 组件 |
| 小组件渲染 | 通过：聊天里直接显示输入框 +「发出去」按钮 |
| 组件内输入 ONLY-A | 通过（需在「点开放大」弹窗内操作；聊天内联位置会被主输入框抢焦点） |
| 点「发出去」回显 | 通过：uiautomator dump 确认回显文本 ONLY-A 出现在 center=(540,1452.5) |
| A/B 两个组件状态隔离 | **未完成**：需再让 TA 生成第二个不同 HTML 组件并分别输入，成本较高未继续 |

证据：[html05-widget-a-created.png](html05-widget-a-created.png)、[html05-widget-expand-dialog.png](html05-widget-expand-dialog.png)、[html05-widget-a-input.png](html05-widget-a-input.png)、[html05-widget-a-echo.png](html05-widget-a-echo.png)、[html05-widget-a-echo-proof.txt](html05-widget-a-echo-proof.txt)

**环境已恢复**：「对话里的小玩意儿」已关回关闭状态，输入法已恢复讯飞。

### 自动化限制记录（供后续参考）

真机 adb 自动化在以下场景不可靠，已实际踩到：

| 场景 | 现象 | 规避方式 |
|---|---|---|
| `input text` 长字符串 | 被截断或丢字符 | 分段输入，每段 < 15 字符 |
| 讯飞输入法接收 ASCII | 被联想成中文（如 msg1 → Vmsg） | 切 AOSP LatinIME (`com.huawei.ohos.inputmethod/...LatinIME`) |
| 中文 `input text` | 直接丢失 | 无法输入中文，改用英文名 |
| Compose 输入框 `keyevent 67` 删除 | 部分残留删不掉 | 发送该条消息清空，或重开会话 |
| HTML 小组件内联位置点击 | 焦点被聊天输入框抢走 | 打开「点开放大」弹窗后在弹窗内操作 |
| 键盘弹出/收起 | 输入栏与发送按钮位置变化 | 每次操作前 uiautomator dump 重新定位 |
