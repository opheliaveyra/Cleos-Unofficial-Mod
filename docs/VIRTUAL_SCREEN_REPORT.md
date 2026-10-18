# 虚拟屏：可行性与真机验证报告（红米 Android 14）

- **版本**：0.1.84-mod（探针改造未单独升版本；下一版统一）
- **设备**：红米 23076RA4BC / Android 14（API 34）/ 1080×2460
- **目标**：角色在独立虚拟屏里刷抖音（启动 → 看到画面 → 滑动），不影响主屏

## 本轮结论：整条链路**可行**，但需要 ADB 级权限

| 步骤 | 做法 | 结果 |
|---|---|---|
| 建虚拟屏 | MediaProjection（用户点「立即开始」授权）+ mediaProjection 前台服务 + registerCallback | ✅ displayId=2，720×1280，dpi 320，状态 ON |
| 把抖音放到该屏 | **adb shell** `am start --display 2 -n com.ss.android.ugc.aweme/.splash.SplashActivity` | ✅ 无 SecurityException |
| 读画面 | 探针用 ImageReader 读虚拟屏 Surface | ✅ 720×1280，采样非黑点 9504（整帧有内容） |
| 向该屏滑动 | **adb shell** `input -d 2 swipe 360 1000 360 300 300` | ✅ 滑动前后帧 md5 不同（c14590… → 2eee4c…），画面真的变了 |

## 0.1.60 的假设被修正的地方

1. 当时以为「创建虚拟屏不需要权限」——**Android 14 上不对**：需要 `CAPTURE_VIDEO_OUTPUT` 或 **MediaProjection token**。
2. Android 14 还要求：投屏 token 必须运行在 **mediaProjection 类型的前台服务**里，且建屏前必须 **registerCallback**（否则 IllegalStateException）。
3. `am start --display` / `input -d` 确实需要 ADB 级权限（MANAGE_ACTIVITY_TASKS / INJECT_EVENTS）；**adb shell 与 Shizuku 是同一档**，实测 adb 全部可用。

## 探针本轮改动（`ui/probe/`）

- `<queries>` 声明抖音与 Shizuku 包（修包可见性导致的"抖音没装"误报）
- `MediaProjectionService`：mediaProjection 前台服务
- 创建虚拟屏改走 MediaProjection（先起服务 → 拿 token → registerCallback → createVirtualDisplay）
- 「启动抖音」「滑动」保留 App 内尝试（预期 SecurityException），并**给出对应的 adb 命令**（`am start --display` / `input -d`）

## 未做 / 风险

- 帧未导出到电脑做视觉确认（`filesDir` 里的 PNG 只验证了非黑与变化）；要看"是不是抖音界面"需要下一步导出或截图对比。
- 没集成 Shizuku 库（构建时依赖未解析）；生产化要么让 App 通过 Shizuku 执行命令，要么接受"电脑 ADB 常驻"。
- 荣耀（Android 10）没有公开 createVirtualDisplay，本轮无法覆盖。
- 声音输出、发热、抖音反作弊/检测虚拟屏、登录（扫码/验证码）都未验证。
- 主屏与虚拟屏同时使用时，抖音生命周期/降优先级未验证。

## 下一步建议

1. 探针加"导出帧到 Download"（或直接屏幕外截图对比），确认画面就是抖音。
2. 生产化：集成 Shizuku（App 内调 `am/input`）或明确的 ADB 辅助模式。
3. 在 0.1.85 里把探针的 adb 命令显示做成可复制。
