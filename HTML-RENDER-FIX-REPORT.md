# HTML 渲染与放大预览：修复报告

版本：`0.1.66-mod`（versionCode 238）
范围：聊天里的 ```html 小玩意儿（`LiveWidget`），**只动 HTML 渲染与放大，不碰 Markdown/代码块/气泡样式**。

## 一、根因分析

### 已确认（有代码或测试依据）

1. **内容在「字符串 → 解析」阶段没有丢**。`LiveWidgets.split` 用「标题 + 两段文字 + 按钮 + 输入框 + 长内容」
   样例做过单测（`LiveWidgetSampleTest`），切出来的 HTML 与原文完全相等 —— 2/2 通过。
   所以问题不在消息预处理、不在 `split`。
2. **气泡里的高度上限是硬截断**。旧实现：`heightPx = document.scrollHeight.coerceIn(60, 2400)`，
   内容超过 2400 CSS px 就不再长高；WebView 高度等于被 clamp 的值，超出的部分留在 WebView 内部，
   但列表里看就是「底部被截断」。
3. **放大预览此前是固定高度或依赖 `fillMaxHeight()`**：
   - 0.1.64 之前：`Dialog` 里 `WebBox` 写死 `height(420.dp)`，内容超过 420dp 直接被父容器裁掉；
   - 0.1.64 之后：改成 `AndroidView.fillMaxHeight()`，但 **Compose 给 `AndroidView` 的约束经
     `View.onMeasure` 后不保证变成 WebView 的实际高度**，于是出现「弹窗很大、HTML 只占顶部一小块、
     剩下全是空白、又滚不动」——高度和内容高度都对不上。
4. **图片/字体加载完高度不会更新**：旧实现只在 `onPageFinished` 量一次；图片加载后页面变高，容器没跟着变。
5. **宽内容被静默压制**：`page()` 里 `overflow-x: hidden` + `* { max-width: 100% }`，
   宽表格被压窄，压不下的部分被藏掉，用户没有任何提示。

### 推测（没有真机日志，需复现确认）

- 若修复后仍「只显示顶部」，最可能是模型 HTML 里某个容器带 `height` + `overflow: hidden`
  （0.1.65 已加 `overflow: visible !important` 兜底），或页面里出现了未闭合的标签/脚本导致解析中断。
  判断方法见第四节。

## 二、修改文件与关键实现

`app/src/main/java/com/cleo/cleos/ui/chat/LiveWidget.kt`

- **`WebBox` 重写为两种高度模式**（不再用固定大高度掩盖问题）：
  - 气泡（`limit = 360.dp`）：高度 = `min(量出来的内容高度, 360dp)`；超限时调用方把提示改成
    「内容较长，点开放大看全部」。加载前按 `80 + html.length/8` 估个高度，避免加载那一刻跳动。
  - 放大（`limit = null`）：用 `BoxWithConstraints` 取可用高度，WebView 高度 =
    **`min(内容高度, 可用高度)`** —— 短内容贴合内容（不留大片空白），长内容填满可用区域并在
    **WebView 内部滚动**（`isVerticalScrollBarEnabled = true`）。
- **测量方式**：`onPageFinished` 后测一次，再用 `postDelayed` 在 350ms / 1200ms 各测一次
  （等图片、字体加载完）；取 `max(html.scrollHeight, body.scrollHeight, body 盒高)`；
  固定次数，不会无限更新循环。
- **每个 WebBox 独立 WebView**（气泡一个、放大一个，各跑各的），`DisposableEffect` 里
  `stopLoading()` + `destroy()`：连续打开/关闭不残留。
- **`page()` CSS**：`overflow-x: hidden` 改为 `body { overflow-x: auto }`，`table { max-width: none }`，
  保留 `* { max-width: 100% }` 做响应式压缩 —— 宽表格优先压缩，压不下可横向滚，不再静默裁掉。
- **安全策略未改**：`blockNetworkLoads = true`、`allowFileAccess/allowContentAccess = false`、
  `shouldOverrideUrlLoading` 一律 return true、**没有** `addJavascriptInterface`。

## 三、构建与测试结果

- `:app:assembleDebug`：**BUILD SUCCESSFUL**（本机 JDK 21 + Android SDK，非模拟器）。
- `:app:testDebugUnitTest`：**139 套件 / 993 用例全绿**（含 `LiveWidgetSampleTest` 2 个）。
- 新增/更新的报告：本文件。

## 四、尚未解决的问题与复现方式

**无法在本机验证的（需要真机）**：

- 验收表里的 9 项：单行 / 多段 / 按钮输入框 / 长篇滚动 / 宽表格 / 图片加载 / CSS 布局 /
  连续开关 / 不同屏幕尺寸 —— 都需要装到手机上手动看。本机只有编译与单测。

**复现与定位方法**（如果还有问题）：

1. 装 `0.1.66-mod`，在聊天里让 TA 写一段长 HTML；
2. 电脑上执行 `adb logcat -s CleosWidget`；
3. 会看到每次测量的 `measure srcHtml=… px=…`：
   - `srcHtml` 是原始 HTML 长度；
   - `px` 是页面量出来的高度；
   - 如果 `px` 很小但 `srcHtml` 很大 → 页面内容被 CSS 裁掉或解析中断（内容问题）；
   - 如果 `px` 很大而界面还是只显示一截 → 容器/裁剪问题（布局问题）。
4. 需要临时关掉诊断：删 `TAG` 常量和 `measureOnce` 里的 `Log.d` 即可。

**已知遗留**：

- 气泡里内容超 360dp 时，WebView 自身仍可内部滚动，和聊天列表的滚动手势可能打架；
  目前靠「点开放大」兜底，没有做「气泡内彻底禁止滚动」。
- 放大弹窗是全屏 Dialog（`usePlatformDefaultWidth = false`），不同厂商 ROM 的键盘/边距表现未逐一验证。
