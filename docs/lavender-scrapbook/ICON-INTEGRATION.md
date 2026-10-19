# 紫雾手账 · 全 App 图标接入（第一批）

日期：2026-10-20。分支：`feat/lavender-scrapbook-local`。
素材来源：用户提供的完整版总览图（`docs/lavender-scrapbook/icons-final/`），无重绘、无改风格。

## 接入机制

新增 `ui/theme/ScrapbookIcons.kt`：

- `ScrapbookIconMap.byVectorName`：以 **ImageVector.name** 为键的 `Map<String, Int>`，把语义匹配的 Material 图标映射到 `R.drawable.scrap_*`。四种风格（Filled / Outlined / Rounded / AutoMirrored）都登记，调用点用哪种都能命中。
- `CleosIcon(imageVector, contentDescription, modifier, tint)`：签名对齐 `Icon`，内部只做一件事 ——
  `LocalVisualTheme.current == LAVENDER` 且映射命中 → 画拼贴 PNG（**不套 tint**，保留纸色与花/丝带）；
  否则 → 原样 `Icon(...)`。
- 因为判定发生在渲染层而不是数据层，**原作者默认主题逐字节不变**：同一份代码，`native_default` 下全部走原生图标。

## 已接入范围（100 个调用点 / 31 个文件）

| 区域 | 文件 | 覆盖内容 |
|---|---|---|
| 底部导航 | `ui/theme/LavenderScrapbook.kt` | 聊天 / 日记 / 待办 / 主页 四入口，选中放大 |
| 聊天页 | `ChatScreen.kt`、`ChatInputParts.kt` | 顶部对话列表、输入栏 +、搜索、语音、播放、展开收起 |
| 聊天页操作 | `MessageActionMenu.kt` | 更多/收起、复制/收藏/回复等 |
| 群聊 | `GroupChatScreen.kt` | 返回、发送、附件、相机、群组 |
| 主页 | `HomeScreen.kt` | 设置齿轮、添加 TA、信箱、记忆、游戏入口 |
| 设置页 | `SettingsPages.kt`、`AppPages.kt` | 资料与性格、世界书、模型、说话方式、服务商、模型配置、能做的事、工作区、声音、外部服务、外观 |
| 日记 | `DiaryListScreen.kt`、`DiaryEditorScreen.kt` | 筛选、编辑、锁、图片、日历 |
| 待办 | `TodoScreen.kt` | 添加、勾选、日期、展开 |
| 收藏 / 记忆 / 信件 / 搜索 / 通话 | 各自 Screen | 语义匹配处 |
| 玻璃组件 | `glass/GlassComponents.kt` | `GlassIconButton` 也走 `CleosIcon`，所以原版按钮在 lavender 下同样换皮 |

## 统计

| 指标 | 数量 |
|---|---|
| 素材总数 | 50 |
| 已接入素材 | 43 |
| CleosIcon 调用点 | 100 |
| 涉及文件 | 31 |
| 覆盖页面/区域 | 12 |

## 未使用素材（9）

| 素材 | 原因 |
|---|---|
| `bubble_recv / send / typing / voice / image / file / reply / system`（8 个） | 这是「撕纸气泡」素材，属于第二批（气泡渲染重写）。当前气泡仍用原版的 Glass 气泡，**没有为了用掉而强行塞进按钮**。 |
| `mute` | 只在通话/媒体静音场景出现，当前通话页沿用原生 `MicOff`（表达的是"关麦"不是"静音喇叭"），暂不替换。 |

## 缺失清单（保留原生图标，24 项）

以下语义**没有对应素材**，按规则保留原生 Material 图标，不硬凑：

`ArrowUpward`、`ArrowDownward`、`ArrowDropDown`、`ExpandLess`、`ExpandMore`、`Call`、`CallEnd`、`ContentCopy`、`DynamicFeed`、`EmojiEmotions`、`FileDownload`、`GridOn`、`Key`、`Lightbulb`、`Lock`、`LockOpen`、`Palette`、`Send`、`SkipNext`、`Stop`、`TextFields`、`Widgets`、`AlternateEmail`、`MarkEmailUnread`

其中 `Send`（纸飞机）、`Stop`、`Lock` 是高频道图标，后续补素材收益最大。

## 已知需要复核

- 日记页右下 FAB 的`写日记`图标、待办页`添加`FAB 的内层图标，在实机截图上仍显示为原生白色 —— 这两处可能是自定义绘制而非 `Icon()` 调用，下一批单独处理。
- 应用图标（启动器）不在此次范围，仍走 0.1.93 的 32 套 `ic_appicon_*`。

## 真机截图

`shots/icons/01-chat.png` ~ `05-todo.png`（荣耀 YAL-AL50，紫雾手账模式）。
