# HTML 小玩意儿 · 气泡内预览 → 卡片 + 全屏查看器

日期：2026-10-20。分支：`feat/lavender-scrapbook-local`（改动独立于主题，原版主题同样生效）。

## 目标

聊天里不再渲染被裁的 HTML；改成卡片入口 + 独立全屏查看器。状态恢复、重置、数据零丢失。

## 修改文件清单

| 文件 | 改动 |
|---|---|
| `ui/chat/LiveWidget.kt` | **主要改动**。`LiveWidget` 从「内嵌预览 + 弹窗放大」改成「卡片 + 全屏查看器」；删除 `WidgetPreview`、`WidgetBox` 的按内容测量高度逻辑、`PreviewWebView`（不消费触摸的预览 WebView）、`measureOnce`；`WidgetViewer` 改为全屏 Dialog（`usePlatformDefaultWidth=false` + `decorFitsSystemWindows=false` + `BackHandler` + `imePadding`）；新增 `FullScreenWidget`（`fillMaxSize`，不量高度）；`makeWebView` 去掉 `interactive`/`onHeight` 参数，恒为可交互；`page()` 的 CSS 从「预览裁切」改为「全屏可滚」；新增 `widgetTitle()` 从 `<title>` 取卡片标题 |
| `ui/chat/LiveWidget.kt`（状态脚本） | 重置逻辑修正：先设 `window.__cleosNoSave=true` 掐掉保存，再 `removeItem` 再 reload —— 否则 `beforeunload` 会把旧值又写回去（实测复现过） |

**没有动**：`MessagePart` 切分逻辑、`split()`、`stableKey()`、`stateScript()` 的存储格式、`LocalLiveWidgets`/`LocalCards` 开关、ChatScreen 的调用点、任何数据表。

## 行为变化

| 之前 | 现在 |
|---|---|
| 气泡里直接跑 WebView，最多 80% 屏，超出裁掉、触摸穿透 | 一张卡片：小玩意儿 / 标题 / HTML · 可交互 / 打开查看 |
| 点「点开放大」弹 80% 对话框 | 点卡片进**全屏查看器**，占满屏幕 |
| 长内容只能靠放大后的 80% 弹窗滚 | 全屏 WebView 自己滚，不再裁切 |
| 预览和放大各建一个 WebView | 只为查看器建一个 |

安全策略一字未改：不联网（`blockNetworkLoads`）、不碰文件（`allowFileAccess/allowContentAccess=false`）、不许跳出去（`shouldOverrideUrlLoading` 全拦）、没有 JS 桥。

## 真机测试（荣耀 YAL-AL50 / Android 10 / 1080×2340）

用「设置 › 关于 › HTML 渲染自检」的六种样例实测：

| 项 | 结果 | 证据 |
|---|---|---|
| 聊天里不再出现内嵌 HTML | ✅ | 六种样例全部变成卡片，无一渲染 WebView |
| 卡片内容 | ✅ | 标题取 `<title>`，没有就显示「HTML 小玩意儿」；类型行「HTML · 可交互」 |
| 点卡片进全屏查看器 | ✅ | 占满屏幕，不透明背景，顶部栏「重置状态 / 关闭」 |
| 短内容完整显示 | ✅ | 短 HTML 样例整块显示，没有多余空白 |
| 全屏滚动 | ✅ | 长内容在查看器内滚动（视口不再是 80% 上限） |
| 输入 + 软键盘 | ✅ | 输入框可聚焦、键盘弹出，查看器内容上移不被遮挡 |
| 变量/表格/按钮 | ✅ | 宽表格、交互按钮样例正常渲染可点（HTML/CSS/JS 能力保留） |
| 返回手势 / 返回键 | ✅ | 关闭查看器回到聊天（列表），不退出聊天页；`mCurrentFocus` 仍为 MainActivity |
| 状态恢复 | ✅ | 输入内容后关闭再开，输入框仍保留原值 |
| 重置状态 | ✅ | 修好后点一下：localStorage 清空 + 页面 reload，输入框回到 placeholder |
| 数据不丢 | ✅ | 只动了渲染层，记忆/日记/聊天记录都没碰；`stableKey` 未变，老存档仍能对上 |

## 已知限制（没变）

- 状态恢复只覆盖表单值（input/textarea/select/checkbox）和滚动位置；JS 闭包、定时器、Canvas/WebGL 像素不在能力范围内。
- 卡片标题只在 HTML 有 `<title>` 时才有意义，否则统一显示「HTML 小玩意儿」。
- `HtmlSelfTest.kt` 里那句说明文案还是旧的（「预览最多 80% 屏；点『点开放大』」），该文件本身有历史编码问题，未在该文件里改中文，避免把乱码扩散。

## 验收标准对照

- 聊天中不再出现被裁切的 HTML 预览 → ✅
- 点击卡片可以完整打开并正常操作小玩意儿 → ✅（滚动、点击、输入、返回、状态恢复、重置）
