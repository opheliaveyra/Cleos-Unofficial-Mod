# HTML 小玩意儿：返回重开后状态丢失（修复报告）

- **版本**：`0.1.82-mod`（versionCode 254）
- **任务**：GitHub Issue #3
- **真机**：荣耀 YAL-AL50 / Android 10（部分验证，见 §4）

## 1. 根因（源码确认）

`ui/chat/LiveWidget.kt` 的 `makeWebView`：
1. `settings.domStorageEnabled = false` —— 页面的 localStorage/sessionStorage 被禁用；
2. `loadDataWithBaseURL(null, …)` —— 没有稳定 origin，即使开存储也无处按页面存放；
3. WebView 每次打开都新建（`remember(html, interactive)` + `onDispose { destroy() }`），
   返回重开 = 全新页面，JS 内存状态自然丢。

## 2. 修复（最小改动）

- **稳定作用域**：`stableKey(html)` = SHA-1 前 10 字节（20 位十六进制）。同一段 HTML 同 key，内容变了就是另一份状态。
- **固定 origin**：`loadDataWithBaseURL("https://cleos.widget/<key>/", …)`；`blockNetworkLoads = true` 不变，这个假域名只用来给 WebView 的存储分区，不指向任何真实服务。
- **开启 DOM storage**：`domStorageEnabled = true`；关掉 Cookie 接受（`setAcceptCookie(false)`）。
- **受控快照/恢复**（注入一段页面内脚本，不暴露任何 native 桥）：
  - 恢复：`input/textarea/select` 的值、checkbox/radio 的选中、`window.scrollY`；
  - 保存：监听 `input/change/scroll`，存进本 key 的 localStorage；
  - 加载后恢复一次，300ms 再补一次（应对延迟渲染）。
- **WebView 可聚焦**：`isFocusable/isFocusableInTouchMode = true`，放大模式下点输入框能弹键盘。
- 安全策略保持不变：不联网、不读文件、不允许跳转、无 `addJavascriptInterface`。

## 3. 能恢复 / 不能恢复（如实）

| 类型 | 结果 |
|---|---|
| 表单值（text/textarea/select/checkbox） | ✅ 恢复 |
| 页面滚动位置 | ✅ 恢复 |
| 普通 HTML 里的静态 DOM | ✅ 本来就在 HTML 里 |
| JS 闭包 / 变量（如按钮点击后变字的 DOM 修改） | ❌ 不恢复 |
| 定时器、动画、Canvas/WebGL 像素 | ❌ 不恢复 |
| 跨 HTML | ✅ 按 key 隔离，不串档 |
| HTML 内容更新 | 新 key = 新状态（旧状态自然隔离） |
| 杀进程后 | localStorage 在 WebView 数据目录里，理论上仍在；**未逐项真机验证** |

## 4. 测试与真机

- 单测：`LiveWidgetStateTest` 3 个（同内容同 key、不同内容不同 key、key 长度/十六进制）。
- 全量：**149 套件 / 1058 用例全绿**；Debug 构建成功。
- 真机（0.1.81/0.1.82）：设置 → 关于 → HTML 渲染自检可正常打开，六种样例渲染正常；
  **输入框状态恢复的真机复测未完成**（修完焦点后上下文/时间不够，尚未逐项验证）。
  这部分在报告里不写成"通过"。

## 5. 已知限制 / 未做

- **「重置此 HTML 状态」按钮未做**（Issue 要求项）。
- 刷新（下拉刷新）不是本实现的行为，未验证。
- 恢复只覆盖可识别 UI 状态，不宣称恢复任意 JS 状态。
- 预览模式的 WebView 仍然不消费触摸（保持聊天列表可滚）。
