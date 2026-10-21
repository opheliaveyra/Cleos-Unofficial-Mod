# 紫雾手账 UI V1（33 件素材）· 进度报告

日期：2026-10-21。**没有发布 APK、没改签名、没动用户数据。**

## 一、素材已全部入库并核对

来源：夸克网盘「Cleos ui图片素材 / cleos_lavender_ui_v1_33_assets.zip」（2.2M，今天 11:08 上传）。
33 件全部复制到 `res/drawable-nodpi/scrap_ui_*.png`，manifest.csv 和 README 也存进 docs。

核对结果：33 件全部在线、尺寸与 manifest 一致、alpha 0~253（四周透明、中心不透明）。

## 二、已经接入的（4 件，全部生效）

| 素材 | 用在哪 | 怎么接的 |
|---|---|---|
| `scrap_ui_title_home.png` | 主页标题 | `GlassTopBar` 里替换掉玻璃胶囊，纸签自带「主页 Home」字样 |
| `scrap_ui_title_diary.png` | 日记标题 | 同上，进日记页即生效 |
| `scrap_ui_title_todo.png` | 待办标题 | 同上 |
| `scrap_ui_title_chat.png` | 聊天标题 | 同上 —— 但聊天页有副标题（TA 名字+模型），**这条分支不会命中**，见下 |

一处改动同时覆盖四个页面：`GlassTopBar` 里判断「紫雾主题 + 标题是这四个 + 没有副标题」就画纸签、不再画文字。
纸签自带文字，所以代码这边的 `Text(title)` 被跳过 —— 这就是 README 里说的「处理文字重复」。

## 三、还没接入的（29 件）和原因

### 因为「图里有示例文字」必须改结构的（12 件）

| 素材 | 卡在哪 | 需要怎么处理 |
|---|---|---|
| `title_chat` | 聊天页有副标题（TA 名 · 模型），纸签只有一行 | 要么纸签只当标题、副标题单独摆一行；要么让 Aster 出一版不带文字的纸签 |
| `tabs_diary` | 日记分类（全部/我写的/TA 写的/小秘密）是**动态选中态**，图是静态的四个标签 | 需要九宫格切 + 选中态叠加，或者出一版单个标签 |
| `bubble_self` / `bubble_ta` | 气泡图里有示例文字，且气泡要随内容拉伸 | 需要**九宫格**（三边拉伸、四角固定），直接铺会压字 |
| `input_chat` / `input_todo` | 输入框要随宽度拉伸，图里有占位文字 | 九宫格 + 占位文字由代码画 |
| `empty_diary` / `empty_todo` | 空状态卡片里有「还没有日记」这类文字 | 可以整张用，但**文字就固定死了**；现在代码里那句话是动态的 |
| `toast_success` / `toast_error` | 提示条里的文字是动态的（说具体错误） | 只能当底板 + 代码画字，需要九宫格 |

### 因为交互/选中态还没设计的（6 件）

| 素材 | 卡在哪 |
|---|---|
| `avatar_frame_large` | 主页花环框，和现在用的 `scrap_avatar_pair_combo` 是一套，换了要重新对齐圆心 |
| `avatar_frame_selected` / `_online` / `_offline` / `_default` | 这几个是**状态框**，但现有代码里没有「在线/离线/选中」这套状态 |
| `navigation_scrapbook` | 整条导航栏底板（4 个图标+文字都在图里），但现在选中态是靠胶带拖动的，图是静态的 |

### 可以直接接、只是还没排到的（11 件）

`button_add_circle`、`button_confirm`、`button_cancel`、`button_calendar`、`button_more`、`button_heart`、`button_star`、`button_edit`、`button_delete`、`button_next`、`button_add_floating`、`search_field`、`selector_character`、`notice_system`

这些是透明素材（图里没字），按 32~44dp 摆上去就行，但要逐个确认：
- 点击区域不能被图缩小（现在按钮的 touch 区域是按原尺寸算的）
- 原有功能的图标（比如编辑/删除）现在用的是 CleosIcon 的纸绘图标，换之前要确认不是重复替换

## 四、需要 Aster 重新制作的素材

| 素材 | 为什么 |
|---|---|
| `bubble_self` / `bubble_ta` | 需要**不带文字的纯气泡底**（现在是带示例文字的长条，没法九宫格） |
| `input_chat` / `input_todo` | 同上，需要不带占位文字的纯输入底板 |
| `toast_success` / `toast_error` | 同上 |
| `tabs_diary` | 需要四个**单独**的标签（现在是排好的一整条，做不了选中态） |
| `title_chat` | 需要不带文字的一版，或者接受「标题纸签 + 副标题另起一行」的布局 |

## 五、编译与真机

- `assembleDebug` BUILD SUCCESSFUL
- 真机（荣耀 YAL-AL50）主页标题已变成纸签，截图见 `01-home-paper-title.png`
- 版本号已还原成 0.1.97 / 269，没有打包发布

## 六、下一步建议顺序

1. 导航栏底板 + 11 个按钮（透明素材，风险低）
2. 等 Aster 出「不带文字」的气泡/输入框/提示条，再做九宫格
3. 头框四件套要先把「在线/离线/选中」这套状态定义出来
