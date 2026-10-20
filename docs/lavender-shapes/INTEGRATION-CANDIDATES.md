# 紫雾手账 · 形状素材用途映射与接入预留

日期：2026-10-21。**本文件是建议表，不是替换表。** 没有 Aster 和玖玖确认之前，任何形状都不进真实 UI。

## 一、状态

| 项 | 状态 |
|---|---|
| 形状登记 | ✅ 28 个，manifest v3（`status: candidate`，`repository_status: present`） |
| PNG 上传 | ✅ **28 张全部已进 `assets/lavender-scrapbook` 分支**（`86245d6`） |
| 代码接入 | ❌ 一个都没接，全部 `candidate` |
| 现有 UI | 未改动（背景、胶带选中态、已接入图标、原版主题全部保持） |

素材来源：`cleos_lavender_ui_shape_assets_20261021.zip`（5.6 MB）。
包里还带了 `source/` 两张原始 sheet（带标注 / 不标注）和 `previews/ui_shape_collection_preview.png` 总览，都一起入库了。

## 二、28 个形状 → 用途建议

优先级：P1 = 最适合先选；P2 = 备用/点缀。

### 卡片容器候选

| 素材 | 形状 | 建议用途 | 接入方式 | 优先级 |
|---|---|---|---|---|
| `frame_rectangle_paper` | 直角矩形 | 内容面板、设置分组 | 九宫格 | **P1** |
| `frame_round_note` | 圆角便签 | 弹窗、小卡片 | 九宫格 | **P1** |
| `frame_rectangle_grid` | 网格纸 | 表单/资料卡 | 网格纹理要平铺，不能拉伸 | P2 |
| `frame_round_ribbon` | 圆角+丝带 | 便签容器 | 九宫格（四角不拉） | P2 |
| `frame_oval_blue` | 椭圆 | 椭圆容器 | 固定尺寸 | P2 |

### 聊天气泡候选

| 素材 | 形状 | 建议用途 | 接入方式 | 优先级 |
|---|---|---|---|---|
| `bubble_speech_cream` | 对白框 | 收发消息气泡 | **必须先做九宫格** | **P1** |
| `bubble_round_lilac` | 提示气泡 | 系统提示、typing 气泡 | 九宫格 | **P1** |
| `frames/frame_speech_note_lavender` | 对话框便签 | 大卡片式消息 | 固定尺寸 | P2 |

> 注意：目前聊天里正在用的 `scrap_bubble_*` 是**已经验证过的撕纸气泡**，这俩是候选备份，不是替换。

### 标签 / Chip 候选

| 素材 | 形状 | 建议用途 | 优先级 |
|---|---|---|---|
| `frame_capsule_lilac` | 淡紫胶囊 | 状态标签、筛选 Chip | **P1** |
| `frame_capsule_cream` | 暖白胶囊 | 次级按钮底形 | **P1** |
| `frame_oval_wreath` | 椭圆花环 | 柔软标签 | P2 |

### 分隔线候选

| 素材 | 用途 | 接入方式 | 优先级 |
|---|---|---|---|
| `divider_stitch` | 列表分隔（替换现在 1px 虚线） | 横向平铺 | **P1** |
| `divider_lace` | 分组标题下的花边 | 横向平铺 | **P1** |
| `wave_divider_cream` / `wave_divider_blue` | 内容分区波浪 | 横向平铺 | P2 |

### 通话按钮 / 圆形按钮底形候选

| 素材 | 用途 | 优先级 |
|---|---|---|
| `frame_circle_flower` | 静音/挂断按钮底形（现在用的是自绘圆 + 描边） | **P1** |
| `frame_circle_crescent` | 头像装饰 | P2 |
| `ring_flower` | 头像光环（可替换现在那圈细线） | P2 |

### 加载 / 圆环状态候选

| 素材 | 用途 | 接入方式 | 优先级 |
|---|---|---|---|
| `ring_progress_segments` | 等待回复/生成中的环 | 旋转遮罩或切帧 | **P1** |
| `ring_flower` | 通话等待呼吸环 | 固定尺寸 + alpha 动画 | P2 |

### 背景纹理 / 其他

| 素材 | 用途 | 优先级 |
|---|---|---|
| `pattern_dots` / `pattern_grid` | 卡片内衬纹理、空状态背景 | P2 |
| `arrow_chevron_pair` / `arrow_curved` | 设置页 chevron 替换 | P2 |
| `shape_star_lavender` / `shape_heart_pink` | 收藏、消息 reaction | P2 |
| `shape_plus_lavender` / `shape_close_cross` | 新增/关闭的基础形状 | P2 |
| `shape_triangle_play` / `shape_diamond_lavender` | 播放指示、徽章 | P2 |

## 三、接入预留点（代码位置）

真的开始接的时候，按这张表改，不用再满仓库找：

| 想换的东西 | 现在在哪 | 换法 |
|---|---|---|
| 聊天气泡底 | `ui/chat/ScrapBubbles.kt` 的 `ScrapBubble()` + `drawNinePatch()` | 已有九宫格绘制，换个 res 就行 |
| 卡片容器 | `glass/GlassComponents.kt` 的 `GlassSurface` → lavender 走 `scrapbookSurface()` | 在 `scrapbookSurface()` 里按 shape 选素材 |
| Chip / 标签 | `ui/settings/SettingsScreen.kt` 的 `Chip()` | 加 lavender 分支 |
| 分隔线 | `ui/settings/SettingsPages.kt` 的 `RowDivider()` | 换成 drawImage 平铺 |
| 通话按钮 | `ui/call/CallScreen.kt` 的 `ScrapCallButton()` | 现在是圆底+描边，可以换 `frame_circle_flower` |
| 等待圆环 | `CallScreen.kt` 的 `Rings()` / `ChatScreen.kt` 的 `TypingDots()` | 环用 `ring_progress_segments` |
| 设置页 chevron | `ui/settings/SettingsPages.kt` 的 `Entry()` 右侧 Icon | 换 `arrow_chevron_pair` |
| 背景纹理 | `ui/theme/LavenderScrapbook.kt` 的 `ScrapbookBackground()` | 不建议动，固定背景已定稿 |

## 四、接入顺序建议（等确认后）

1. **分隔线**（改动最小，视觉收益最大）
2. **Chip 胶囊**（设置页大量用到）
3. **卡片容器**（影响面大，要单独一轮）
4. **聊天气泡**（已经有一套在跑，换要谨慎）
5. **通话按钮底形**（配合之前那轮通话改版）
6. 其余点缀

## 五、硬约束

- 所有形状只在 `VisualTheme.LAVENDER` 下生效
- 原版默认主题的 `Wallpaper()`、`GlassSurface`、Chip、分隔线一行不改
- 不改已定稿的：固定背景、底部胶带选中态、已接入的 66 个纸绘图标
- 非九宫格素材不做任意长度拉伸
