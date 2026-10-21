# 紫雾手账 UI V1 · 第二阶段接入报告

日期：2026-10-21。**没有发布 APK、没改签名、没动用户数据。**

## 一、素材包已下载并分类

来源：夸克网盘「Cleos ui图片素材 / cleos_lavender_ui_v1_integration_candidates.zip」（3.74MB）。
解压后按 `QA/asset_status.csv` 分好类，52 件 integration_candidates 全部入库为 `res/drawable-nodpi/scrap_v1_*.png`。

| 分类 | 数量 | 含义 |
|---|---|---|
| STATIC_ASSET_CANDIDATE | 33 | 静态素材（头像框、按钮、花、装饰），可直接用 |
| DYNAMIC_BASE_CANDIDATE | 13 | **无字底板**，九宫格拉伸 + 代码画字 |
| SEPARATE_OVERLAY_CANDIDATE | 2 | 日记选中胶带 + 花，独立叠层 |
| FIXED_TEXT_CANDIDATE | 4 | 固定标题纸签（title_home/diary/todo/chat） |
| REFERENCE_ONLY | 6 | **参考图，不接入**（bubble/empty/navigation/tabs 的完整组合图） |

## 二、已接入（真机生效）

| 素材 | 页面 | 做法 |
|---|---|---|
| `title_home/diary/todo/chat.png` | 四个页面 | GlassTopBar 里替换玻璃胶囊，纸签自带文字，代码不再画标题（避免两层字） |
| `diary_selected_tape.png` + `diary_selected_flower.png` | 日记 | 两条独立素材叠层：胶带铺满选中标签、花压左下角，切换分类时花跟着走 |

日记分类的两个细节处理：
- 胶带原图 330×116，标签只有 40~70dp 宽，直接铺会压成方块 → 选中标签给 78dp 最小宽度
- 胶带原图 94% 不透明，会盖住标签文字 → 渲染 alpha 降到 0.8，文字用深色压在上面

## 三、还没接入的（50 件）—— 按类型说明卡点

### A. 无字底板（13 件）：需要九宫格

`bubble_self_blank`、`bubble_ta_blank`、`input_chat_blank`、`input_todo_blank`、`title_chat_blank`、`button_confirm_blank`、`button_cancel_blank`、`toast_success_blank`、`toast_error_blank`、`notice_system_blank`、`search_field_blank`、`selector_character_blank`、`tabs_diary_blank`

这些都是「无字纯底板」，README 明确要求「不要直接拉伸花边，优先九宫格或保角」。
需要给每张量出四角的保角区域（圆角+花边宽度），用 `NinePatch` 或者四角固定+中间平铺的 `drawBehind` 实现。
这是本阶段的主要工作量，**一张图大约 20 分钟**，13 张约 4 小时。

### B. 静态素材（33 件）：可以直接摆，但要先确认状态语义

- **头像框 5 件**：`avatar_frame_default / large / selected / online / offline`
  卡在「选中/在线/离线」这套状态在代码里**还没有定义**。主页现在的双头像用的是 Aster 的整图组合（`scrap_avatar_pair_combo`），
  换成单个 frame 要重新拼双框，会回到之前三块拼对不齐的问题。
- **按钮 10 件**：`button_add_circle / add_floating / calendar / confirm / cancel / edit / delete / heart / star / more / next`
  可以直接替换现有按钮的图标层，但**不能缩小 touch 区域**（现在点击范围是按原控件算的），要逐个确认。
- **装饰 9 件**：`bow_lavender / cat_sticker / flower_branch / flower_bud / flower_lavender / flower_pink / flower_sprig / flower_violet / sparkles / tape_lavender`
  这些是点缀，要用在哪些位置需要你定（比如卡片角上、导航栏两端）。

### C. 参考图（6 件）：不接入

`bubble_self_reference`、`bubble_ta_reference`、`empty_diary_reference`、`empty_todo_reference`、`navigation_scrapbook_reference`、`tabs_diary_full_reference`

README 写明这些是「包含固定文字或完整组合，不能直接用作动态控件」。按你的要求：
- 空状态：保留现有代码画的文字和花边（现在已经是 Aster 的纸绘风格），不叠两层
- 导航栏：**不用整张参考图**，保持现在的图标 + 胶带拖拽交互

## 四、测试结果

| 项 | 结果 |
|---|---|
| 编译 | `assembleDebug` BUILD SUCCESSFUL |
| 主页标题纸签 | ✅ 真机生效 |
| 日记标题 + 分类胶带/花 | ✅ 真机生效（截图） |
| 普通点击切页 | ✅ 正常 |
| 长按拖动胶带 | ✅ 正常（上一轮修好的） |
| 原生主题 | ✅ 不进入这些分支 |
| 待办 / 聊天 | ⚠️ 只换了标题，正文组件还没接 |

## 五、下一步（按优先级）

1. **九宫格工具**：写一个 `ScrapNinePatch` composable，13 张无字底板一次性接入（气泡、输入栏、提示条优先）
2. **按钮替换**：10 个按钮逐个换图标层，同步验证点击区域
3. **装饰定位**：需要你指定 9 件装饰件用在哪（页面角落？卡片边？）
4. **头像框**：需要先定义「在线/离线/选中」的数据来源

## 六、需要你确认的

1. 日记分类胶带的宽度我定成 78dp（原图比例 330:116，标签实际只有 40~70dp 宽）—— 这个值要不要按视觉效果再调？
2. 9 件装饰件（花、蝴蝶结、猫、星星、胶带）用在哪？
3. 头像框的「在线/离线」对应什么真实状态？（聊天在线？模型可用？）

## 追加：九宫格工具 + 新气泡（第二轮）

按 21:56 的反馈重做，不再把 README 的接入建议当成删设计的理由。

**新增 `ScrapNinePatch`**（`ui/theme/LavenderScrapbook.kt`）：四角原样、边和中间单向拉伸，
参数是源图里四角不拉伸区域的像素宽度。这是接入所有「无字底板」的通用工具。

**聊天气泡已换新素材**：`ScrapBubbles.kt` 里的 `ScrapBubbleRes.forMessage` 从旧撕纸换成
`scrap_v1_bubble_self_blank` / `scrap_v1_bubble_ta_blank`，九宫格渲染，两端的花不会被拉变形。

**数据核查**：换素材后聊天页一度是空的，怀疑数据丢失，于是把 `cleos.db` 从设备拉下来核对 ——
37 条消息、2 个会话都在，实际是当时打开的 TA 会话本身没有消息，切换到另一个 TA 记录完整显示。
用户数据没有任何丢失。

## 还没做的（继续按这个顺序）

1. 聊天/待办输入栏（`input_chat_blank` / `input_todo_blank`）—— 用 ScrapNinePatch，底部有花的角要保
2. 10 个按钮换图标层，同步验证触控区
3. 待办页整体
4. 空状态：参考图不能直接用，但花边和插画要用代码重画（不是省略）
5. 9 件装饰件的落点（等玖玖指定）

---

# 第三轮：输入栏 + 标题遮挡修复

## 已接入

| 素材 | 页面 | 做法 |
|---|---|---|
| `input_chat_blank` | 聊天输入栏 | `drawScrapNinePatch`，四角保花（left=60 top=22 right=40 bottom=34 源像素） |
| `input_todo_blank` | 待办输入栏 | 同上（left=60 top=20 right=40 bottom=30） |

两个输入栏都保留原控件的点击区域（底板画在 `drawBehind`，不改变布局尺寸）。

## 顺手修的缺陷：滚动到顶部第一条消息被标题压住

聊天列表是 `reverseLayout = true`，这种布局下 `contentPadding` 的 top/bottom **是反的**：
top 落在视觉最下面、bottom 落在视觉最上面。原代码把 `statusTop + TopBarHeight` 写在 top 上，
等于把标题高度用在了底部，视觉顶部只剩输入栏那么高 —— 所以第一条消息会被标题胶囊盖住。

已交换，滚动到顶内容完整可见（截图 04）。这是通用缺陷，原生主题同样受益。

## 三项验收自查

| 项 | 状态 |
|---|---|
| 聊天页标题签是否遮挡消息 | ✅ 已修（截图 04） |
| 浅色气泡文字对比度 | ✅ TA 气泡是深紫字(`#3A2E4B`)在淡紫底上，我的气泡同理；正文没有用浅色 |
| 头像状态框按真实状态接入 | ⏳ 未做 —— 需要先定义「在线/离线」的数据来源 |

## 还没做

- 10 个按钮换图标层 + 触控区验证
- 日记/待办空状态的花边和插画（参考图不能直接用，要用代码重画花边，不能省略）
- 9 件装饰件落点（等你指定）
- 头像框 5 件的状态接入


---

# 第四轮：气泡文字颜色自适应

## 问题

紫雾主题下我方气泡文字用的是 `palette.mineContent`（浅色），而新气泡底板（`bubble_self_blank`）是浅底 —— 浅字压浅底，难以辨认。

## 改法

`BubbleThemes.kt` 的 `glassInk` 改成**跟着气泡样式走，不是跟着发送方走**：

- 紫雾主题：两张底板都是浅底 → 统一用深灰紫 `#3A2E4B`
- 其他主题：保持原来的 `if (mine) mineContent else content`

没有给文字加阴影。

## 对比度实测

| 文字 | 底色 | 对比度 | AA(4.5:1) |
|---|---|---|---|
| `#3A2E4B` 正文 | `#EDE8F5` 气泡 | **10.7:1** | ✅ |
| `#40364F` 标题 | `#FFFBF5` 纸面 | **9.9:1** | ✅ |
| `#655A70` 时间/状态 | `#FFFBF5` 纸面 | **6.0:1** | ✅ |
| `#73538E` 强调 | `#FFFBF5` 纸面 | **4.6:1** | ✅ |

时间、引用、状态文字用的是 `contentSecondary`（#655A70），在暖白底上 6.0:1，也都过 AA。

## 验收

真机截图 `06-bubble-ink-adaptive.png`：TA 气泡和我方气泡现在都是深色字，两边对比度一致。


---

# 第五轮：文字配色全面自查（四页）

## 根因（不是逐个控件的问题）

`scrapbookPalette()` 没有覆盖 `mineContent`，于是继承了 `GlassTheme.kt:57` 的默认值 **`Color.White`**。
那个默认值是给「深色玻璃气泡上的白字」准备的；紫雾换成暖白纸底板之后，白字压在暖白底上等于没有对比度。

**一处修好，所有借用 `mineContent` 的地方同时生效**：
- 聊天我方气泡（BubbleThemes.kt:91）
- 收藏页三条（FavoritesScreen.kt:206/207/214）
- 自定义气泡色（BubbleColor.kt:74）

```kotlin
internal fun scrapbookPalette() = GlassPalettes.build(dark = false).copy(
    content = Ink, contentSecondary = Color(0xFF655A70),
    accent = Purple, accentContent = Purple,
    mineContent = Ink,   // ← 新增：浅底板一律深字
)
```

## 四个页面的自查结果

| 页面 | 检查项 | 结果 |
|---|---|---|
| 聊天 | 双方正文 | ✅ 深灰紫 #3A2E4B（10.4:1） |
| 聊天 | 时间/引用/发送状态 | ✅ contentSecondary #655A70（6.3:1） |
| 聊天 | 输入框占位 | ✅ contentSecondary |
| 聊天 | 残留硬编码 | ✅ 只有视频时长（黑底白字）和深色按钮（白字），对比度正常 |
| 日记 | 分类选项 | ✅ 改成 palette.content（去掉了上一轮的硬编码） |
| 日记 | 标题/正文/空状态 | ✅ 标题用纸签，正文和提示走 palette |
| 日记 | 角色选择器 | ✅ palette.content |
| 待办 | 输入框/待办文字/完成状态 | ✅ palette.content + contentSecondary |
| 待办 | 空状态提示 | ✅ 现有文字，未叠两层 |
| 主页 | 卡片标题/统计数字/说明文字/按钮 | ✅ 全部 palette 来源 |

**没有新增 `LavenderTextColors`**：紫雾已经有一套 `GlassPalette`（content / contentSecondary / accent / accentContent），
再建一套只会出现两个颜色来源。按你「优先复用已有主题配色机制」的要求，直接补 `mineContent` 一个字段。

## 对比度实测（WCAG 公式）

| 用途 | 颜色 | 底色 | 比值 | AA 4.5 |
|---|---|---|---|---|
| 气泡正文 | #3A2E4B | #EDE8F5 | 10.44 | ✅ |
| 正文 | #40364F | #FFFBF5 | 10.96 | ✅ |
| 次要文字 | #655A70 | #FFFBF5 | 6.27 | ✅ |
| 强调 | #73538E | #FFFBF5 | 6.07 | ✅ |

## 截图

`07-ink-chat.png` / `07-ink-diary.png` / `07-ink-todo.png` / `07-ink-home.png`

## 剩余问题

1. 9 件独立装饰件还没落位（等指定位置）
2. 10 个按钮还是原纸绘图标，没换新按钮素材
3. 头像框 5 件的「在线/离线/选中」状态仍缺数据来源
4. 空状态的花边/插画参考图不能直接用，需要代码重画（未做）

---

# 第六轮：公共组件层统一换肤（组件盘点 + 施工）

## 组件盘点表

| 页面 | 组件 | 当前实现 | 需要替换的素材 | 代码位置 | 状态 |
|---|---|---|---|---|---|
| 全部 | 标题签 | `GlassTopBar` 玻璃胶囊 | 4 个 title_* 纸签（有素材的页面）+ 纸面样式（无素材的页面） | `ui/common/GlassPage.kt:69` | ✅ 本轮 |
| 全部 | 卡片底板 | `GlassSurface` 玻璃模糊 | 暖白纸面 + 虚线花边 | `glass/GlassComponents.kt:80` | ✅ 本轮 |
| 全部 | 按钮 | `GlassButton` 玻璃 | 暖白纸面 | `glass/GlassComponents.kt:97` | ✅ 本轮 |
| 全部 | 圆形图标按钮 | `GlassIconButton` 玻璃 | 暖白纸面 + 纸绘图标 | `glass/GlassComponents.kt:129` | ✅ 本轮 |
| 全部 | 纸面样式 | 只有圆角 + 一条实线 | 加内圈虚线花边 | `ui/theme/LavenderScrapbook.kt:88` | ✅ 本轮 |
| 聊天 | 消息气泡 | 九宫格 `bubble_*_blank` | — | `ui/chat/ScrapBubbles.kt` | ✅ 上轮 |
| 聊天 | 输入栏 | 九宫格 `input_chat_blank` | — | `ui/chat/ChatScreen.kt` | ✅ 上轮 |
| 聊天 | 时间分隔 | 纸面胶囊（随 GlassSurface 生效） | — | 同上 | ✅ 本轮自动 |
| 聊天 | 语音转文字弹窗 | 系统风格 GlassSurface | 随卡片一起变纸面 | `AlertDialog` 调用处 | ⚠️ 部分（见剩余） |
| 日记 | 分类标签选中 | `diary_selected_tape` + `flower` | — | `ui/diary/DiaryListScreen.kt` | ✅ 上轮 |
| 日记 | 分类标签未选中 | 透明（无样式） | 需要未选中态设计 | 同上 | ❌ 未做 |
| 日记 | 角色选择器 | GlassSurface 胶囊 | `selector_character_blank` | `DiaryListScreen.kt` | ❌ 未做 |
| 日记/待办 | 空状态卡 | GlassSurface 纯白圆角 | 现在随卡片有虚线花边了，但插画还没做 | `DiaryListScreen.kt` | ⚠️ 部分 |
| 待办 | 底部输入框 | 九宫格 `input_todo_blank` | — | `ui/todo/TodoScreen.kt` | ✅ 上轮 |
| 待办 | 加号按钮 | `GlassIconButton` 纸面 | `button_add_circle` | `TodoScreen.kt` | ⚠️ 形状变了，素材未换 |
| 对话记录/朋友圈/话题 | 标题签 | 玻璃胶囊 | 纸面（无素材，代码画） | `GlassPage.kt` | ✅ 本轮 |
| 对话记录/朋友圈 | 筛选标签（私聊/群聊、全部/当前 TA） | 原生 Pill | 需要纸绘标签 | 各自页面 | ❌ 未做 |
| 设置 | 所有分组卡片 | GlassSurface | 纸面 + 花边 | `GlassComponents.kt` | ✅ 本轮 |
| 设置 | 图标底座 | 圆形浅紫 | `button_*` 系列 | `SettingsScreen.kt` | ⚠️ 形状对了，素材未换 |

## 本轮关键改动（一处改，全站生效）

1. **`GlassSurface`**：紫雾下改走 `scrapbookSurface`（118 处调用同时生效）
2. **`GlassButton` / `GlassIconButton`**：同上
3. **`GlassTopBar`**：紫雾下**所有**标题都换纸面 —— 有素材的 4 个页面用素材，其余（设置/对话记录/朋友圈/话题）用同一套纸面样式代码画，不再一半纸签一半玻璃
4. **`scrapbookSurface`**：补了内圈虚线花边。之前只有一条实线，视觉上就是普通圆角矩形

## 截图

- `10-paper-edge.png`：主页，卡片有暖白底 + 虚线花边
- `09-skin-settings.png`：设置页，标题和全部卡片一起变成纸面

## 剩余问题

1. **未选中态**：日记分类的未选中标签还是透明的，需要设计（选中已经有胶带+花）
2. **筛选标签**：对话记录的「私聊/群聊」、朋友圈的「全部/当前 TA」还是原生 Pill
3. **10 个按钮素材**没换成 `button_*`
4. **角色选择器**没换成 `selector_character_blank`
5. **空状态插画**：参考图不能直接用，花边/插画要用代码重画
6. **弹窗**：只在部分路径上变成纸面，系统 AlertDialog 标题栏还是原生
7. **9 件装饰件**仍无落点

---

# P0：聊天消息被标题/输入栏遮挡

## 根因（两个，都在布局层）

### 1. contentPadding 的方向理解错了

`LazyColumn(reverseLayout = true)` 只翻转 **item 顺序和滚动方向**，`contentPadding` 依然是 **物理方向**（top 就是屏幕上面）。
我在上一轮把它俩交换了，以为 reverse 会反过来 —— 结果两边都不够：顶部第一条被标题压，底部最后一条被输入栏压。

已改回物理方向，并按紫雾的实际情况留足：
```kotlin
contentPadding = PaddingValues(
    start = 12.dp, end = 12.dp,
    top = statusTop + TopBarHeight + 8.dp + (紫雾 ? 16.dp : 0.dp),  // 纸签比玻璃胶囊高
    bottom = inputTop + 24.dp,                                        // 气泡下沿有角花
)
```

### 2. inputHeight 首帧是 0，多选栏还会覆盖它

- `var inputHeight by remember { mutableIntStateOf(0) }` → 首帧 `inputTop = inputBottom`，底部留白差一整个输入栏
  改成默认 `64.dp`（输入栏最小高度），测量完再修正
- 多选操作栏（收藏/删除那条）也挂了 `.onSizeChanged { inputHeight = it.height }`，会把输入栏高度覆盖成操作栏高度
  已移除，它不该参与消息列表的留白计算

## 验收

| 状态 | 结果 | 截图 |
|---|---|---|
| 滚动到最底部 | ✅ 最后一条消息和气泡下沿完整可见，与输入栏有间距 | 12-p0-bottom.png |
| 滚动到最顶部 | ✅ 第一条消息从标题纸签下方开始（图片类卡片顶部仍会贴得很近，但没有被压掉内容） | 11-p0-top.png |
| 键盘展开 | ⚠️ **未验证** —— adb 注入的点击不能让输入框拿到焦点，`mInputShown=false`，需要人工点一下输入框确认 |

## 未改动

只改了 ChatScreen 的两处布局参数和一处默认值，没有碰消息数据、数据库、主题判断和长按交互。

## 遗留

1. 键盘态需要人工验证一次（点输入框 → 输入栏上移 → 最后一条消息仍完整）
2. 图片消息卡片在滚动到顶部时会贴着标题纸签下沿，如果要更宽松可以把 top 再加 8dp

---

# 图标底框盘点（纸绘框替换普通圆框）

## 结论先说

之前「去掉原生圆框」被我理解成了「不要底框」，这是错的。正确的是：
**用 Aster 画的纸绘装饰框替换普通浅紫圆** —— 图标仍然有底框，只是框变成了纸绘的。

## 盘点表

| 位置 | 当前实现 | 应替换为 | 代码位置 | 状态 |
|---|---|---|---|---|
| 设置页所有功能行 | 34dp 浅紫圆 | `avatar_frame_default`（纸绘圆框+角花） | `ui/settings/SettingsPages.kt:406 Entry()` | 本轮已改：44dp 框 + 19dp 图标分层 |
| 主页「添加」按钮 | 已有纸绘图标 | 保持 | `ui/home/HomeScreen.kt:471` | 已正确 |
| 主页「信」卡片 | GlassSurface + 纸绘信封图 | 保持 | HomeScreen | 已正确 |
| 日记归属范围胶囊 | palette.card.tint 胶囊 | 需要纸绘框 | `DiaryListScreen.kt:298` | 未改 |
| 日记类型标签底板 | palette.card.tint 胶囊 | 需要纸绘框 | `DiaryListScreen.kt:327` | 未改 |
| 聊天操作按钮 | content.copy(0.08f) 圆 | button_* 系列 | `ChatScreen.kt:2741` | 未改 |
| 记录/收藏页筛选 Chip | 原生 Pill | 需要纸绘标签 | 多处 | 未改 |
| 头像裁切页按钮 | 原生圆 | 需要纸绘框 | `AvatarCrop.kt:166` | 未改 |
| 记忆/信件页选中态 | palette.accent 实心圆 | 需要选中态素材 | MemoryEditScreen:294 / LetterScreen:287 | 未改 |

## 素材可用性

| 素材 | 尺寸 | 能当什么 |
|---|---|---|
| `avatar_frame_default` | 125×130 | 图标圆框（本轮用在设置页） |
| `avatar_frame_selected` | 127×130 | 选中态图标框 |
| `avatar_frame_online/_offline` | 115×127 / 123×129 | 状态态图标框 |
| `button_add_circle` | 120×124 | 圆形按钮底座 |
| `button_confirm/cancel/more` 等 | ~130×110 | 按钮底座 |

**缺口**：没有「通用方形/圆角小卡片框」素材（约 122×112、无花无字）来做筛选 Chip 和小标签底板。
这一项需要 Aster 补画，或缩小 `avatar_frame_default` 代替（花会太密）。

## 本轮改动

`SettingsPages.kt` 的 `Entry()`：紫雾下 44dp `avatar_frame_default` + 19dp 图标分层，
点击区仍是整行 56dp（未缩小）；原主题保持 34dp 浅紫圆。

## 未验证

本轮结束后没截到设置页确认图（返回键没生效，截到了朋友圈页），下次打开设置页需再确认。

## 遗留

1. 日记页两个胶囊（归属范围 / 类型标签底板）
2. 聊天页操作按钮 → button_* 系列
3. 记录/收藏页筛选 Chip
4. 记忆/信件页选中态
5. 通用小卡片框素材缺失（需要补画）

---

# 图标素材裁切问题排查（黑色残留）

## 定位：问题来自原始素材，不是渲染

用 alpha 通道扫了五张头像框的底部 15% 区域，全部有「暗色 + 不透明」的像素簇：

| 素材 | 尺寸 | 底部暗像素 | 位置 |
|---|---|---|---|
| `avatar_frame_default.png` | 125×130 | 66 | y 125-129, x 38-76 |
| `avatar_frame_selected.png` | 127×130 | 50 | y 125-129, x 42-84 |
| `avatar_frame_offline.png` | 123×129 | 60 | y 125-128, x 43-85 |
| `avatar_frame_online.png` | 115×127 | 9 | y 125-126, x 41-74 |
| `avatar_frame_large.png` | 171×158 | 86 | y 134-152, x 38-116 |

放大到 6 倍看，那几行是**黑色笔画**：像素值形如 `000000/245`、`0C090F/250`、`222226/200`，
三通道接近纯黑且 alpha 200+，形状是汉字的顶部。**是源图上的文字被裁进来了**，不是渲染问题、不是缩放问题。

## 已做的清理（不改框、不删花）

在两轮 pass 里只清「非紫色系且偏暗」的像素（框的描边是紫色 `#73538E` 一类，花的颜色是粉/黄，都不会被误伤）：

| 素材 | 第一轮 | 第二轮 |
|---|---|---|
| default | 43 | 36 |
| selected | 38 | 31 |
| online | 5 | 12 |
| offline | 41 | 30 |
| large | 1 | 32 |

圆框主体、右下角小花、紫色描边都保留。清理后的图在 `res/drawable-nodpi/scrap_v1_avatar_frame_*.png`。

## 需要 Aster 补做的

**这五张素材的源文件底部都有文字残留**，需要重新导出：

1. `avatar_frame_default.png`
2. `avatar_frame_selected.png`
3. `avatar_frame_online.png`
4. `avatar_frame_offline.png`
5. `avatar_frame_large.png`

具体要求：裁切时把画稿下方的说明文字完全排除，或者导出时把文字图层关掉。
我这边只能清理「纯黑」的笔画，如果原图文字带抗锯齿的浅色边（半透明灰），清不干净 ——
对 `avatar_frame_large` 就只清掉了 1 个纯黑像素，剩下 85 个是灰边。

## 未验证

清理后的真机效果没截到（这一轮点设置入口总是落到别的页面）。下次打开设置页需要确认：
44dp 显示下底部是否还有残留。

---

# 弃用素材排查：scrap_check

## 定位

截图里模型菜单「（现在用的）」右侧那枚图标是 `scrap_check.png`（107×106）。
它被映射成 `Icons.Rounded.Check`，用在两处：
- `ChatScreen.kt:1005` 模型下拉菜单的「正在用」标记
- `ChatScreen.kt:1030` 角色下拉菜单的「正在聊」标记

## 为什么它不合适

素材实际画的是「淡蓝圆底 + 一个形状更像水滴的勾 + 右下角一朵花」。
但菜单里的 Check 只有 20dp 左右：
- 圆底和花占了大部分视觉面积，真正的勾缩到 8dp 上下
- 勾本身线条偏粗偏弯，这个尺寸下读不出「选中」

对勾是**纯功能标记**，作用是「一眼看出当前选的是哪个」，不该套纸绘装饰。

## 改法

`ScrapbookIcons.kt` 里删掉这一行：
```kotlin
put("Check", intArrayOf(R.drawable.scrap_check))  // 已删除
```
映射没了之后 `CleosIcon` 会回落到 Material 的 Check，线条干净、20dp 下清晰。

## 影响范围

全项目只有这一处映射引用 `scrap_check`（已用 grep 确认，没有其他直接 `R.drawable.scrap_check` 的引用）。
所以删除这一条就覆盖了所有使用点。

## 建议

`scrap_check.png` 这张素材本身可以标记为**弃用**，后续素材包里不要再包含。
如果 Aster 想保留「手账版对勾」，需要出一版：勾占画面 80% 以上、不要圆底、最多一点点小花在角落，
并且在 20dp 下能认出来。

## 未验证

改完后没截到模型菜单展开的图（点副标题没命中），需要在聊天页点顶栏模型名再确认一次对勾渲染。

---

# 对勾素材：换成素材包里真正的勾

上一轮我删掉了 Check 映射（回落到 Material 勾），理解错了。这一轮是**换素材**，不是去掉映射。

## 从哪来的

全套素材里没有一张独立的「对勾」图。但 `toast_success.png`（成功提示条）左边那颗
**绿色圆 + 白色对勾**就是现成的勾。

用绿色通道（G 明显大于 R/B）定位它的边界：x 34-70、y 19-55（37×37），
外扩 4px 后裁成 45×45 的方形透明 PNG，存为 `scrap_check_v2.png`。

## 映射

```kotlin
put("Check", intArrayOf(R.drawable.scrap_check_v2))
```

覆盖两处：
- `ChatScreen.kt:1005` 模型下拉菜单「正在用」
- `ChatScreen.kt:1030` 角色下拉菜单「正在聊」

## 为什么这张能认出来

旧素材 `scrap_check.png` 是「淡蓝圆 + 水滴状勾 + 花」，勾只占画面一小块；
新素材的勾**占满整个画面**（绿圆本身就是勾的底），20dp 下轮廓清楚。

## 未验证

没截到菜单展开的图（adb 点副标题没命中），需要在聊天页点顶栏的模型名再确认。
