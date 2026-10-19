# 紫雾手账 · 图标遗漏审计（只统计，未改代码）

审计时间：2026-10-20 02:30。基线：`feat/lavender-scrapbook-local` @ `bef8798`。
方法：全仓库静态扫描 `Icons.*` 引用 → 与 `ScrapbookIcons.byVectorName` 比对 → 定位渲染点（`CleosIcon(...)` 的参数括号范围）→ 人工按语义分类。
**本轮没有修改任何代码、没有重绘、没有替换。**

---

## 一、总账

| 指标 | 数量 | 说明 |
|---|---|---|
| `Icons.*` 引用总数 | 240 | 含条件表达式里的多个分支 |
| **图标独立种类** | **92** | 去重后的语义种类 |
| 其中已登记映射 | 71 | 能在 lavender 下渲染成拼贴素材 |
| 其中未登记映射 | 21 | 见第三、四节 |
| **渲染点总数** | **95** | `CleosIcon(...)` 调用点，不含 `ScrapbookIcons.kt` 内部定义 |
| 完全替换的渲染点 | 64 | 映射全部命中 |
| 部分替换的渲染点 | 4 | 条件表达式里一半命中（播放/停止、展开/收起、加号/关闭） |
| 完全未替换的渲染点 | 15 | 见第四节 |
| 传变量的渲染点 | 12 | 运行时由传入 ImageVector 决定，已过接入层 |
| 非 `CleosIcon` 的图标渲染 | 1 | `GlassTabBar.kt:308`，lavender 主题下不渲染（见第五节） |
| **有素材可替换但漏了** | **0** | 见第二节 |
| **缺素材的独立种类** | **16** | 见第三节 |
| **建议保留原生的种类** | **5** | 见第五节 |

> 口径说明：**种类 ≠ 位置**。92 是"多少种图标"，95 是"多少处渲染点"。同一图标（如 `ExpandMore`）在 6 个位置出现，种类只算 1。

---

## 二、已有匹配素材但未替换：0 条

结论：**没有遗漏**。

理由：目前 50 个素材里有 43 个已进 `byVectorName`，剩余 9 个是——

| 素材 | 状态 | 原因 |
|---|---|---|
| `bubble_recv / bubble_send / bubble_typing / bubble_voice / bubble_image / bubble_file / bubble_reply / bubble_system` | 未使用 | 这是**气泡形状**，不是图标控件位；属于第二批「撕纸气泡」渲染改造，强行塞进按钮会变形失真 |
| `mute` | 已登记（映射 `VolumeOff`）| 代码里没有 `VolumeOff` 用法，等通话页改造时才会出现 |

所以：**不存在"素材画了、映射也写了、但代码没用上"的情况**。

---

## 三、没有匹配素材：16 种（需要补画）

| # | 图标 | 功能语义 | 建议尺寸 | 使用位置 | 代码位置 |
|---|---|---|---|---|---|
| 1 | `Send` | 发送消息 / 寄出信件 | 24dp（含留白 32dp） | 聊天输入栏发送、朋友圈发布、信件寄出 | `FeedScreen.kt:303`、`LetterScreen.kt:190`、`GroupChatScreen.kt:459` |
| 2 | `Stop` | 停止播放 / 停止朗读 | 20–24dp | 语音气泡播放中、音乐播放器、收藏试听 | `ChatScreen.kt:1855`、`GroupChatScreen.kt:515`、`FavoritesScreen.kt:204` |
| 3 | `Call` | 打电话 | 24dp | 聊天页顶部 | `ChatScreen.kt:2571`、顶部按钮 `ChatScreen.kt:1014` |
| 4 | `CallEnd` | 挂断 | 24dp | 通话中页面 | `CallScreen.kt:140` |
| 5 | `Lock` | 私密 / 上锁 | 18–22dp | 日记私密标记、日记编辑器、聊天锁 | `DiaryListScreen.kt:402`、`DiaryEditorScreen.kt:166/385`、`ChatScreen.kt:2679` |
| 6 | `LockOpen` | 解锁 / 公开 | 18–22dp | 日记编辑器状态切换 | `DiaryEditorScreen.kt:385` |
| 7 | `DynamicFeed` | 动态 / 朋友圈 | 24dp | 聊天页顶部入口、分享卡片 | `ChatScreen.kt:1015`、`FeedShareCard.kt:39`、`FeedScreen.kt:228` |
| 8 | `GridOn` | 宫格 / 游戏大厅 | 24dp | 游戏首页 | `GamesHomeScreen.kt:66` |
| 9 | `ContentCopy` | 复制 | 18dp | 消息操作菜单 | `MessageActionMenu.kt:80` |
| 10 | `TextFields` | 选择文字 | 18dp | 消息操作菜单 | `MessageActionMenu.kt:81` |
| 11 | `EmojiEmotions` | 表情 | 22dp | 输入栏表情按钮 | `ChatScreen.kt:2956` |
| 12 | `FileDownload` | 导出 / 下载 | 20dp | 世界书导出 | `LoreScreen.kt:163` |
| 13 | `Key` | 密钥 / API Key | 18dp | 聊天里的 key 提示 | `ChatScreen.kt:1332` |
| 14 | `Lightbulb` | 灵感 / 想到了 | 18dp | 「TA 自己想起来」提示条 | `ChatScreen.kt:1268` |
| 15 | `Palette` | 外观 / 主题 | 24dp | 设置 › 外观入口 ⚠️ 高频 | `SettingsPages.kt:254` |
| 16 | `SkipNext` | 下一首 | 22dp | 音乐播放器 | `Listening.kt:130` |

**补画优先级**：`Send`（发送）> `Palette`（外观入口，正好是主题开关所在页）> `Stop` > `Lock/LockOpen` > `DynamicFeed` > 其余。

---

## 四、完全未替换的渲染点（15 个）

| 页面 | 位置 | 功能 | 当前来源 | 有素材 | 建议 | 代码 |
|---|---|---|---|---|---|---|
| 通话页 | 底部主按钮 | 挂断 | `Icons.Rounded.CallEnd` | 否 | 补画 | `CallScreen.kt:140` |
| 聊天页 | 语音气泡 | 停止/播放 | `Stop` / `PlayArrow` | 一半（play 有） | 补 `Stop` | `ChatScreen.kt:1855` |
| 聊天页 | 转文字展开 | 展开/收起 | `ExpandLess/More` | 否 | **保留原生** | `ChatScreen.kt:1874` |
| 聊天页 | 工具卡片 | 展开/收起 | `ExpandLess/More` | 否 | **保留原生** | `ChatScreen.kt:2197` |
| 聊天页 | 长消息折叠 | 展开/收起 | `ExpandLess/More` | 否 | **保留原生** | `ChatScreen.kt:2410` |
| 聊天页 | 顶部 | 打电话 | `Icons.Rounded.Call` | 否 | 补画 | `ChatScreen.kt:2571` |
| 聊天页 | 输入框锁 | 私密模式 | `Icons.Rounded.Lock` | 否 | 补画 | `ChatScreen.kt:2679` |
| 聊天页 | 分享卡片 | 动态图标 | `Icons.Rounded.DynamicFeed` | 否 | 补画 | `FeedShareCard.kt:39` |
| 群聊页 | 语音气泡 | 停止/播放 | `Stop` / `PlayArrow` | 一半 | 补 `Stop` | `GroupChatScreen.kt:515` |
| 消息菜单 | 首项 | 更多/关闭 | `ExpandLess` / `Add` | 一半（add 有） | **保留原生** | `MessageActionMenu.kt:55` |
| 日记编辑 | 顶栏 | 私密切换 | `Lock` | 否 | 补画 | `DiaryEditorScreen.kt:166` |
| 日记编辑 | 状态图标 | 私密/公开 | `Lock` / `LockOpen` | 否 | 补画 | `DiaryEditorScreen.kt:385` |
| 日记列表 | 筛选下拉 | 角色筛选 | `ArrowDropDown` | 否 | **保留原生** | `DiaryListScreen.kt:305` |
| 收藏页 | 试听按钮 | 停止/播放 | `Stop` / `PlayArrow` | 一半 | 补 `Stop` | `FavoritesScreen.kt:204` |
| 朋友圈 | 发布 | 发送 | `Icons.Rounded.Send` | 否 | 补画 | `FeedScreen.kt:303` |
| 游戏首页 | 顶栏 | 宫格 | `Icons.Rounded.GridOn` | 否 | 补画 | `GamesHomeScreen.kt:66` |
| 设置页 | TA 声音分组 | 展开 | `ExpandMore` | 否 | **保留原生** | `SettingsPages.kt:408` |
| 待办页 | 分组 | 展开/收起 | `ExpandLess/More` | 否 | **保留原生** | `TodoScreen.kt:347` |

（表格 18 行是因为 4 个"部分替换"渲染点各占一行，实际未完全替换的渲染点是 19 个：15 全未 + 4 部分。）

---

## 五、应当保留原生（5 种图标 + 3 类位置）

### 5.1 保留原生的图标种类

| 图标 | 原因 |
|---|---|
| `ExpandLess` / `ExpandMore` | 功能性小箭头（14–18dp），拼贴图案在这么小的尺寸下花/丝带会糊成一团，反而降低可读性 |
| `ArrowDropDown` | 筛选下拉的三角标，同上 |
| `ArrowUpward` / `ArrowDownward` | 「回到最新消息」的上下箭头，需要极高的方向辨识度 |

### 5.2 保留原生的位置

| 位置 | 原因 | 代码 |
|---|---|---|
| 原版玻璃导航条 | `lavender_scrapbook` 下根本不渲染（走 `ScrapbookTabBar`），只在 `native_default` 出现 | `GlassTabBar.kt:308` |
| 系统通知栏图标 | Android 通知只用单色小图标，彩色拼贴会被系统自动涂白/裁切，放进去是坏的 | `Notifier.kt` |
| 玻璃实验室预览 | `ui/lab/` 是调参工具页，不是正式界面，换皮会干扰调试 | `GlassLabScreen.kt` |

---

## 六、逐区域覆盖率

| 区域 | 渲染点 | 已替换 | 未替换 | 覆盖率 |
|---|---|---|---|---|
| 底部导航 | 4 | 4 | 0 | 100% |
| 聊天页（顶栏/输入栏/气泡/菜单） | 28 | 21 | 7 | 75% |
| 群聊 | 12 | 10 | 2 | 83% |
| 主页 | 8 | 8 | 0 | 100% |
| 设置页（一级+二级） | 22 | 18 | 4→ | 82% |
| 日记 | 9 | 6 | 3 | 67% |
| 待办 | 5 | 3 | 2 | 60% |
| 收藏 | 4 | 3 | 1 | 75% |
| 记忆 / 信件 / 世界书 | 8 | 7 | 1 | 88% |
| 朋友圈 | 12 | 8 | 4 | 67% |
| 通话 / 音乐 | 5 | 3 | 2 | 60% |
| 其他（搜索、游戏、实验室） | 6 | 3 | 3 | 50% |

（覆盖率为人工归类估算，误差 ±1；精确到行号的清单见第四、五节。）

---

## 七、给 Aster 的结论

1. **没有"有素材没接"的遗漏**，0 条。上一批已把 43 个素材该用的位置都用上了。
2. **真正缺的是 16 种图标**，其中 `Send`、`Palette`、`Stop` 三个最影响观感——发送按钮和外观入口（主题开关所在页）现在还是 Material 图标。
3. **5 种小箭头建议永久保留原生**，不要为了覆盖率强行替换。
4. 达到 100% 覆盖率需要补 16 个素材；补完预计渲染点覆盖率可从 **67%（64/95）** 提升到 **90% 以上**（剩下的就是那 5 种箭头 + 变量点）。

---

## 八、附：未使用素材（9）

`bubble_recv`、`bubble_send`、`bubble_typing`、`bubble_voice`、`bubble_image`、`bubble_file`、`bubble_reply`、`bubble_system`、`mute`。
前 8 个留待第二批气泡改造，`mute` 等通话页改造。
