# 紫雾手账 · 撕纸气泡（第二批素材开工）

日期：2026-10-20。只影响 lavender，原版气泡逻辑一行未改。

## 已接上的素材（5/8）

| 素材 | 用在哪 | 状态 |
|---|---|---|
| `scrap_bubble_recv` | TA 的气泡背景 | ✅ 已生效（截图） |
| `scrap_bubble_send` | 我发的气泡背景 | ✅ 已接（未截图验证） |
| `scrap_bubble_voice` | 语音气泡 | ✅ 已接 |
| `scrap_bubble_file` | 文件卡片 | ✅ 已接 |
| `scrap_bubble_reply` | 引用框 | ✅ 已接 |

## 还没接（3/8 + 1）

| 素材 | 计划用在哪 |
|---|---|
| `scrap_bubble_typing` | 「正在输入」指示 |
| `scrap_bubble_image` | 图片消息外框 |
| `scrap_bubble_system` | 系统提示行 |
| `scrap_mute` | 通话页静音图标 |

## 实现

- 新增 `ui/chat/ScrapBubbles.kt`：`ScrapBubble(res, padding)` + 九宫格绘制 `drawNinePatch`。
  素材是固定尺寸长条，气泡宽窄不定，按九宫格切 9 块绘制：四角不拉伸、边和中间拉，
  两端的花和丝带不会被拉变形（四角各留 46dp×14dp）。
- `BubbleThemes.kt` 的 `ChatBubbleSurface` 加一个 `scrapRes` 参数：
  lavender 且无自定义背景/装饰时走 `ScrapBubble`，否则原逻辑。
- 各调用点按语义传素材；不传就用收发默认那张。

## 已验证

- 编译通过、装机运行正常
- TA 的气泡已换成撕纸素材（右下角那朵小花是素材自带的），文字清晰、九宫格没有变形

## 待验证

- 发送气泡（需要实际发一条消息）
- 引用框、文件卡片（需要先有这两类消息）
