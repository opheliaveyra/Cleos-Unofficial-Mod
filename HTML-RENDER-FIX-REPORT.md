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


---

## 补充：0.1.67-mod（截图反馈后）

截图现象：弹窗几乎占满屏幕，HTML 只显示顶部一个粉紫卡片（约 230dp），下面全是弹窗背景；
并且弹窗能上下滑，但内容只有一点点。

### 已确认的根因

1. **弹窗本身撑满 94% 屏幕**。0.1.66 里放大 `Dialog` 用的是
   `Column(fillMaxHeight(0.94f))` + `GlassSurface(weight(1f))` —— 无论内容多高，玻璃背景都占满 94%。
   内容小的时候，下面那一大片就是**弹窗背景**，不是 HTML。
2. **用 `scrollHeight` 测量在放大框里必然失败**。WebView 视口（= 弹窗高度）很大时，
   `document.documentElement.scrollHeight` 恒等于视口高 —— 量出来永远是「满屏」，
   于是 `min(measured, avail)` 永远等于 avail，弹窗缩不下来也不奇怪。
3. **模型页面常写 `min-height: 100vh` / `height: 100%`**，把 body 撑满视口，
   同样让「内容高度」不可信。

### 修复

- 弹窗高度**跟内容走**：`Dialog` 里 `BoxWithConstraints` 取屏幕可用高 × 0.94，
  内层 `Column` / `GlassSurface` 改成 `wrapContentHeight()`，不再 `weight(1f)` / `fillMaxHeight`。
- `WebBox` 放大分支接收弹窗给的 `avail`，高度 = `min(内容高, avail)`；短内容贴合、长内容框内滚。
- **测量改为按内容实际边界**：`body.getBoundingClientRect().height` 与所有后代元素
  `getBoundingClientRect().bottom` 取最大，再 +6px；不再依赖 `scrollHeight`。
- `page()` CSS 增加 `html, body { min-height: 0 !important }` 和
  `body > * { min-height: 0 !important; max-height: none !important }`，抵消模型把页面撑满的写法。

### 验证

- `:app:assembleDebug`：BUILD SUCCESSFUL
- 全量单测：139 套件 / 993 用例全绿
- 真机：未验证（需要装 0.1.67 看效果）。验证方法同上：`adb logcat -s CleosWidget` 看
  `measure srcHtml=… px=…`，`px` 应接近卡片实际高度而不是满屏。

---

## 0.1.72-mod：按需求拆成两种模式（预览 / 放大不共用截断与交互逻辑）

| | 聊天内预览 | 放大查看 |
|---|---|---|
| 宽 | ≤ 屏幕 80% | ≤ 屏幕 80% |
| 高 | 按内容自适应，≤ 屏幕 80% | 按内容自适应，≤ 屏幕 80% |
| 超出上限 | 裁掉，底部提示「内容较长，点开放大看全部」 | 容器锁在上限，WebView 内部纵向/横向滚动 |
| 滚动 | **不允许**；触摸事件不下发（`PreviewWebView.dispatchTouchEvent = false`），聊天列表照常滚 | 允许 |
| 交互（按钮/输入框） | **不执行**（触摸不消费，点不动） | 正常 |
| HTML 源码 | **同一份完整 HTML**（不截断字符串，只裁可见区域） | 同一份完整 HTML，重新 `loadDataWithBaseURL` |

实现要点（`LiveWidget.kt`）：
- `LiveWidget` = `WidgetPreview` + 「点开放大」入口；点击进入 `WidgetZoom`（全屏 Dialog、内容居中）。
- `WidgetBox(html, ink, maxHeight, interactive)`：高度从 72dp 起、**只增不减**地按测量值长到 `maxHeight`；
  超出部分由 WebView 视口裁掉（预览不滚；放大由 WebView 自己滚）。
- `PreviewWebView`：`dispatchTouchEvent` 恒返回 false —— 预览不拦列表滚动，也不执行按钮。
- 测量仍用「跳过撑满视口容器」的 JS，避免 `min-height:100vh` 把高度量成满屏。
- 安全设置不变：`blockNetworkLoads`、禁文件访问、跳转全拦、无 JS 桥。

### 实测结果（本机）

- `:app:assembleDebug`：BUILD SUCCESSFUL（0.1.72-mod / versionCode 244）。
- `:app:testDebugUnitTest`：**140 套件 / 996 用例全绿**（0 失败）。
- `LiveWidgetSampleTest`：短/长/按钮/输入框/宽表格样例的 HTML 在 `split` 阶段逐字符完整。

**未能实测（需要真机）**：短 HTML、长 HTML、宽表格、交互按钮、输入框、动态内容在两种模式下的
实际表现；80% 上限在不同屏幕尺寸/安全区下的观感；预览不拦列表滚动的手感。
复现与定位：`adb logcat -s CleosWidget` 看 `measure srcHtml=… px=…`。

---

## 真机实测（0.1.73 / 0.1.74，荣耀 YAL-AL50 · Android 10 · 1080x2340 · dpi 480）

### 这轮实测挖出来的真正根因（前几版一直没找到）

**CSS px 与 Android dp 的换算错误。** WebView 里 `getBoundingClientRect()` 返回的是 **CSS px**；
页面 viewport 是 `width=device-width`，所以 **1 CSS px = 1dp**。
但代码把测量值又除以了 `density`（这台机是 3）——容器高度只有真实内容的 **1/3**，
看起来就是「只显示上面一小截、下面被裁」。修复：测量值直接当 dp 用，**不再除 density**。

修复前 logcat：`measure srcHtml=152 px=116` → 容器 38dp（内容 116dp，只剩 1/3）；
修复后同一页面短内容 116dp 完整显示。

### 实测结果（ADB + uiautomator + 截图）

| 用例 | 预览（聊天内） | 放大 |
|---|---|---|
| 短 HTML | **完整显示**，内容 116dp，无多余空白 ✓ | **小弹窗**（跟着内容，约 300dp 高）✓ |
| 长 HTML（10 段） | 截断在 80% 屏高（624dp），下方显示 **「内容较长，点开放大看全部」** ✓ | 弹窗 80% 宽高；**框内可滚动**：滑动后从第 9 段滚到第 10 段 ✓ |
| 宽表格（10 列） | 显示表头 10 列，未被静默裁 ✓ | 未单独截图 |
| 交互按钮 | 点预览里的按钮**不执行**（`PreviewWebView` 不消费触摸，事件落给列表）✓ | 点按钮后文字变成「点过了」✓ |
| 输入框 | 预览不执行（同上）— | **未在真机输入验证**（需要 IME 联动） |
| 动态内容（3 秒后长高） | 0.1.74 加了 4 秒后再测一次；**尚未逐帧确认** | — |

### 仍未验证 / 已知限制

- 输入框在放大模式下的 IME 弹出、键盘遮挡：未测。
- 宽表格在放大模式下的横向滚动：未单独截图验证。
- 动态内容 3 秒后长高后容器是否跟着更新：0.1.74 加了 4s 测量，但没做逐帧对比。
- 安全区（刘海/手势条）在不同机型上的 80% 取值：只在 YAL-AL50 上看过。

### 复现方式

1. 装 0.1.74；设置 › 关于 › HTML 渲染自检 › 打开自检；
2. 每块都能看到「点开放大」入口；3. `adb logcat -s CleosWidget` 可看到每次 `measure srcHtml=… px=…`。
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
