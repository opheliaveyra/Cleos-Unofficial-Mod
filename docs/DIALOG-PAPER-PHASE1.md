# 紫雾手账 · 弹窗纸绘化（第一阶段：「换一个 TA」）

日期：2026-10-21。只在 `VisualTheme.LAVENDER` 下生效。

## 一、素材

| 项 | 值 |
|---|---|
| 素材 | `frames/frame_soft_note_lavender.png`（柔边信纸框，1254×1254 RGBA） |
| 仓库路径 | `assets/lavender-scrapbook/frames/frame_soft_note_lavender.png`（分支 `assets/lavender-scrapbook`，提交 `3beae07`） |
| App 内资源 | `res/drawable-nodpi/scrap_dialog_soft_note.png` |
| 为什么选它 | 四角有胶带/花/星星，中间干净，最像「信纸」；撕边便签框按你的要求留给特殊提示弹窗 |

**素材确认存在**，不是只在清单里登记。

## 二、可伸缩实现

**没有整体拉伸。** 新写的 `ScrapbookDialogFrame` 用 Compose Canvas 做九宫格：

- 四角固定（占边长的 22%，胶带和花都在这一圈里）
- 四条边单方向拉伸（线条粗细不变）
- 中间纸面双向拉伸

两个坑和处理：

1. **素材四周有一圈半透明留白** —— 直接铺满会让内容顶到纸面外（第一版截图里标题压在框外）。
   改成把框往外扩 10% 再画，纸面覆盖到 Box 边缘。
2. **内容边距不对称** —— 纸框顶部的内框虚线在 15% 高度处，内容顶部留 56dp、底部 32dp、左右 38dp，
   标题不再压线。

## 三、统一容器

新增 `ui/theme/ScrapbookDialog.kt`：

- `ScrapbookDialogFrame(modifier, content)`：纸绘边框 + 内容安全边距 + 素材缺失回退（拿不到图就只画内容，不崩）
- `usesScrapbookDialog()`：主题判断，原生主题直接走原来的 `AlertDialog`

角色列表抽成 `TaPickerList()`，两个主题共用同一份列表 UI，没有写两份。

## 四、功能验证（荣耀 YAL-AL50）

| 项 | 结果 |
|---|---|
| 纸绘弹窗正常显示、四角装饰无变形 | ✅ 02 |
| 两个 TA 都列出、头像与名字正常 | ✅ |
| 点击角色切换 | ✅ 切到第二个后，原版主题弹窗里那个 TA 变成当前（打勾） |
| 点「关上」关闭 | ✅ |
| 点弹窗外关闭 | ✅ |
| Android 返回键关闭 | ✅ |
| 列表高度自适应（`heightIn(max = 420.dp)`） | ✅ 未固定高度 |
| **默认主题回归** | ✅ 系统 AlertDialog 原样（03） |

## 五、修改文件清单

| 文件 | 改动 |
|---|---|
| `ui/theme/ScrapbookDialog.kt` | **新增**：`ScrapbookDialogFrame` + 九宫格绘制 + `usesScrapbookDialog()` |
| `ui/settings/SettingsPages.kt` | `TaPicker` 分流；抽出 `TaPickerList`；新增 `ScrapbookTaPicker` |
| `res/drawable-nodpi/scrap_dialog_soft_note.png` | **新增**：柔边信纸框 |

没动：角色切换的数据逻辑、设置页其他部分、背景、胶带选中态、图标。

## 六、尚未适配的弹窗（第二阶段清单）

按用途分了类，但**一个都没改**：

| 弹窗 | 现在在哪 | 建议风格 |
|---|---|---|
| 删除确认 | `ChatScreen.kt` / `MessageActionMenu` 相关 | 简洁纸绘框 + 红色警示 |
| 挂断确认 | `CallScreen.kt` 的 `askHangUp` | 简洁纸绘框 + 莓粉警示 |
| 退出编辑 / 放弃 | 多处 `AlertDialog` | 简洁纸绘框 |
| 功能说明 / 权限提示 | `SettingsPages.kt`、`AppPages.kt` 里的说明弹窗 | 撕边便签 |
| 设置里的选择弹窗 | `SettingsPages.kt` 各 Picker | 柔边信纸 |
| 反馈问题 | `FeedbackDialog` | 撕边便签 |
| 许可全文 | `AboutPage` 的 `AlertDialog` | 撕边便签 |

## 七、已知小问题

- 「关上」按钮离纸框下边缘的虚线略近（差几 dp），不影响点击；下一轮可以再把底部边距加 4dp。
- 弹窗高度由内容决定，两个 TA 时偏矮、纸框上下留白不多；TA 多到需要滚动时（>420dp）表现还没测。
