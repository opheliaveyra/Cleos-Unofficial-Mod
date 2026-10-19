# 「一起看」：让 TA 陪你刷视频（验证版报告）

- **形式**：设置 → 关于 → 「一起看（验证用）」→ 打开 → 开始一起看 → 系统投屏授权
- **不用** root / Shizuku；用 MediaProjection（用户点一次授权）

## 实现

- `ui/probe/WatchTogetherActivity.kt`：
  - MediaProjection 建**主屏镜像**虚拟屏，ImageReader 收帧
  - 每 5 秒最多处理一帧；JPEG（宽 480、质量 60）；**字节相同就跳过**（省 token）
  - 通过 `ChatRepository.send(conversationId, "（一起看）…", pictures = [帧])` 发给**当前 TA**
  - 需要 `MediaProjectionService`（mediaProjection 前台服务）与 `registerCallback`
- 设置页关于里加「一起看（验证用）」入口

## 真机验证（红米 Android 14）

- 授权投屏 → 状态「正在一起看」→ **已发 3 帧** ✓
- 聊天里出现「（一起看）这是我正在看的画面…」+ 帧图；**TA 回了两次话**：
  - 「还是这张图啊，连续三次发一样的。这水平虚线看着挺规律，有点像某种信号干扰…」
  - 「同一张图发四次了 😂 …」
  → **TA 真的看到了图并在评论**，Vision 通道打通 ✓

## 已知问题

- 帧内容是**水平虚线/条纹**（看起来像干扰），不是真实界面：大概率是 MediaProjection 捕获了**自己 App 的画面**（递归/重复帧）或编码/像素格式问题，需要下一步修（例如只发"非本 App"的帧、或修正 RGBA buffer 的 stride）。
- 入口在设置里（还没放进聊天页顶栏）。
- 没做：声音、帧率/清晰度选项、只在聊天页运行时捕获、隐私提示（哪些画面会被发出去）。

## 下一步

1. 修帧质量（stride/递归），确认发出去的是真实屏幕内容。
2. 把开关放到聊天页，做成「一起看」模式（顶栏按钮 + 状态条）。
3. 节流策略可调（场景变化才发），费用提示。

---

## 0.1.92：修掉「截到自己界面」+ 真机验证

**根因**：以前只判断「本页在不在前台」（Activity 的 onResume/onPause），
切回 App 的聊天页/设置页时本页已经 pause，于是照样截屏发给 TA —— TA 看到的是聊天界面，
所以会回一些莫名其妙的话。

**改的**：
- 按**整个 App** 是否在前台判断（进程内 resumed 的 Activity 计数 + 切走 600ms 的缓冲），
  App 在前面时一帧都不发。
- 截帧/缩放/编码移到后台线程（原来在主线程，一起看时手机会卡）。
- **Android 10 会崩**：用了 API 30 才有的 `WindowManager.currentWindowMetrics`，荣耀 YAL-AL50 是 API 29 ——
  改用 `displayMetrics`。
- 横屏宽度不封顶：原来固定「高 720」，横屏时宽会到 1560；改成**最长边 720**。
- 点「停止」后 `stopService` 没收，通知栏一直挂着；Bitmap 重复 recycle、`scaled` 在
  `conversationId == null` 时没回收；状态被 onStop 回调改成「投屏被系统停止了」。
- 页面说明补上「本页面在前台时不会发帧」。

**真机（荣耀 YAL-AL50 / Android 10）**：
- 授权 → 自动退桌面 → 发 3 帧 `348x720`，从 `files/images/watch-*.png` 取出帧图确认是**真实桌面**（无虚线、非 App 界面）。
- TA 回复内容对得上桌面（截图里的 OurPlay / Termux / 中国电信 / Cleos / DSH Mobile、底栏三个图标、状态栏时间）。
- 切回 App 后等 15 秒：`logcat -s CleosWatch` 无新帧。
