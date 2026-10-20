# 紫雾手账 · 动态状态与通话界面

日期：2026-10-21。只在 `VisualTheme.LAVENDER` 下生效，原版主题分支一行未动。

## 一、聊天动态状态

| 状态 | 位置 | 处理 |
|---|---|---|
| 等待回复的「…」气泡 | `ChatScreen.kt` 的 `LiveBubble` → `TypingDots()` | **气泡本身不用改**：它走 `GlassSurface`，在 lavender 下已经是纸面（`scrapbookSurface`）。这次只把三个点从深墨改成淡紫（`0xFF9C86BF`）、直径 7→6dp、间距 5→6dp，跟纸面气泡搭得上 |
| 生成中的 Stop 按钮 | `ChatScreen.kt:2979` `BarIconButton(Icons.Rounded.Stop)` | **没有素材**。剪好的 66 个素材里没有「停止」（第一批有播放/暂停/音量，第二批有快进但没有停止）。按规则**不拿暂停充数**，先保留原生图标，登记到缺口清单 |
| 语音播放 / 暂停 / 停止 | `VoiceBubble` 里的 `Icons.Rounded.Stop / PlayArrow` | 走 `CleosIcon`，`PlayArrow` 已映射到 `scrap_play`（第一批 33 号）；`Stop` 同上缺素材，保留原生 |
| 发送按钮 | 输入栏右侧 | 已经是纸飞机（上一轮改过） |

### 缺口清单（等 Aster 出素材）

| 需要的图标 | 用在哪 |
|---|---|
| **Stop（停止）** | ① 生成回复时的输入栏按钮 ② 语音消息正在播放时的停止键 |

只有一个缺口。补上之后映射一行就能接（`ScrapbookIconMap` 里 `put("Stop", …)`）。

## 二、通话界面（`ui/call/CallScreen.kt`）

### 改了什么

- **两个按钮统一成同一套纸绘规范**（新增 `ScrapCallButton`）：56dp 视觉尺寸、48dp 以上触控、圆底 + 浅紫 1dp 描边、无投影。
  - 静音：暖白纸面 `0xFFFBF7FF`，图标 `scrap_voice`；静音状态换莓粉纸面 + 不同图标（`scrap_bubble_voice`）
  - 挂断：低饱和莓粉 `0xFFF0D8DE`，图标 `scrap_call_end`（第二批那张粉色挂断电话）
  - **去掉了原来的高饱和纯红 `0xFFE5484D` 大圆（72dp）**
- **呼叫中只显示挂断**：`CallPhase.Ringing` 时静音按钮不渲染，避免一个点了没用的按钮在那儿跳
- **头像光环简化**：原来「扫描圈 + 两层厚重光晕」在 lavender 下换成**一圈浅紫细线**（1dp）带极轻呼吸；原版主题保留原来的脉冲/音量响应
- **间距收敛**：按钮间距 56→40dp，按钮标签上边距 8→6dp

### 没动的

- 挂断确认弹窗、`BackHandler`、点屏幕打断、`keepScreenOn`、字幕区、`status()` 文案 —— 全部原样
- 原版主题的所有分支（`else` 里还是原来的玻璃按钮和红色挂断）

## 三、修改文件清单

| 文件 | 改动 |
|---|---|
| `ui/call/CallScreen.kt` | 新增 `ScrapCallButton` 和三个纸面色常量；静音/挂断按钮分支；Rings 的 lavender 分支；呼叫中隐藏静音；间距 |
| `ui/chat/ChatScreen.kt` | `TypingDots()` 的 lavender 分支（颜色/尺寸/间距） |

## 四、构建与验证

- `:app:assembleDebug` **BUILD SUCCESSFUL**（2026-10-21 05:03）
- **真机验证没有完成**：构建完准备装机时设备已经断开（`adb devices` 空），凌晨没有手机可连。
  因此以下截图**一个都没有**，不能算验收通过：
  - 等待回复时 / 正在生成时
  - 语音播放、暂停、停止
  - 正在呼叫 / 接通后 / 静音开与关 / 挂断后
  - 默认主题回归
- 代码层面能确认的：分支都在 `VisualTheme.LAVENDER` 判断里，原版主题走原分支。

## 五、待办

1. 手机接上后按上面清单逐项截图（预计 20 分钟）
2. Aster 补 `Stop` 素材后接入
3. 如果接上后按钮尺寸在真机上偏大/偏小，按实际效果再调 `56dp`
