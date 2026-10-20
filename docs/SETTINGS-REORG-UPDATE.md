# 设置页重组 + 二改版独立检查更新

日期：2026-10-21。分支 `feat/lavender-scrapbook-local`（worktree `cleos-lavender`）。

## 一、审计结论（改之前核实的）

| 项 | 实际情况 |
|---|---|
| 「关于」页功能 | 版本说明、工具诊断（复制异常/最近、标记、清除）、反馈问题、虚拟屏探针、一起看、HTML 自检、上次闪退、后台出错、隐私、许可与版权 —— 全在 `AppPages.kt` 的 `AboutPage()` 一个函数里，约 230 行 |
| 现有更新逻辑 | `Updates.kt` **指向原作者仓库** `jqzhang921-sudo/cleos` 的 `/releases/latest`；只取版本号和更新日志，**不解析 APK 附件**；下载靠外边「蓝奏云」链接 + 自动复制提取码 |
| 更新入口是否在用 | **没在用**：`MainActivity` 里只有一句注释说明「UpdatePrompt 留着不用」，`Updates.check()` 从没被调用过 |
| 更新源 | 原版仓库 + 蓝奏云（`Releases.kt` 里的旧链接），二改版没有自己的源 |
| 版本 | `versionCode 266`、`versionName 0.1.94-mod`、`applicationId com.cleo.cleos`（和原版同一个包名，所以覆盖安装必须签名一致） |
| 签名 | 测试用 `cleos-test.keystore`（alias androiddebugkey），和 0.1.93 那批同一个 |
| 导航 | `SettingsPage` 枚举 + `SettingsScreen` 的 `when(page)` 分发；返回由外层 BackHandler/返回键统一处理 |

## 二、设置结构（前后对照）

改之前 App 段：聊天 / 发语音 / 联网搜索 / 外观 / 用量统计 / 数据与备份 / 关于（一个入口塞了 10 个 Section）。

改之后：

```
App
 ├ 聊天
 ├ 发语音
 ├ 联网搜索
 ├ 外观
 ├ 用量统计
 ├ 数据与备份
 ├ 更新与版本      ← 新增
 ├ 诊断与反馈      ← 从「关于」拆出
 ├ 实验室          ← 从「关于」拆出
 └ 关于            ← 精简
```

原来的入口**一个没删**：聊天/语音/搜索/外观/用量/备份都还在原位，TA 那三组（资料与性格、模型、说话方式）也没动。

## 三、更新与版本（新）

- **源**：`api.github.com/repos/opheliaveyra/Cleos-Unofficial-Mod/releases?per_page=20`
- **只认正式版**：`draft`、`prerelease` 直接跳过；一个 Release 都没有 = 「暂无可用更新」，不拿提交记录或临时构建冒充
- **版本比较**：`UpdateVersions.parts()` 从 tag/versionName 里抠数字段（`v0.1.94-mod` → `[0,1,94]`），逐段比大小 —— 不是字符串字典序，所以 `0.1.100 > 0.1.99` 是对的
- **APK 附件**：从 `assets` 里找 `.apk`，取 `browser_download_url`、`size`、`digest`（GitHub 给的 SHA-256）
- **状态**：有更新 / 已是最新 / 暂无正式发布 / 网络或限流失败（每种都有对应中文提示）
- **自动检查**：启动时查一次（`LaunchedEffect`，不阻塞界面）；24 小时节流（`UpdateRules.shouldCheck`）；可在页面里关掉
- **弹窗**：发现新版本才弹；「稍后」和「跳过此版本」都在；不会每次进页面重复弹

### 下载 / 校验 / 安装

用户点「下载更新」才开始：

1. 下到 `cacheDir/updates/`（FileProvider 已放开这一层），显示进度和大小
2. 校验：非空 + 大小一致 → SHA-256（如果 Release 给了）→ 解包看 `packageName == com.cleo.cleos` → 比对签名证书（`GET_SIGNING_CERTIFICATES`）
3. 任何一步不对就删文件、说原因、给「重试」；不会留下半截包
4. 通过后给「安装」按钮 → FileProvider + `ACTION_VIEW` 交给系统安装器；**没有静默安装**
5. 没授「安装未知应用」时，跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 让你自己授权，回来再点
6. 包名同、签名同 → 直接覆盖安装，**不要求先卸载**

## 四、诊断与反馈 / 实验室

- 诊断：工具诊断四个按钮、反馈问题、上次闪退、后台出错 —— **代码原样搬过来**，`ToolDiagnostics` / `CrashLog` 的读写和存储格式一个字没改
- 实验室：虚拟屏探针、一起看、HTML 自检 —— 入口和权限提示照旧，只是换了页面

## 五、关于（精简后）

只剩：名称与版本、二改版说明（首句就是「Cleos 非官方二次修改版，与原作者发布的版本不同」）、原作者与原项目链接、二改版仓库链接、隐私、许可与版权（含全部第三方署名与「看许可全文」）。

## 六、修改文件清单

| 文件 | 改动 |
|---|---|
| `Updates.kt` | 整体重写：换源、解析 assets、版本比较、下载/校验/安装、状态流 |
| `ui/settings/UpdatePage.kt` | **新增**：更新与版本页 |
| `ui/settings/AppPages.kt` | `AboutPage` 精简；新增 `DiagnosticsPage()`、`LabPage()` |
| `ui/settings/SettingsPages.kt` | 枚举加 `Update/Diagnostics/Lab`；App 段加三个入口；关于副标题 |
| `ui/settings/SettingsScreen.kt` | 三个新页面的分发 |
| `ui/settings/SettingsViewModel.kt` | `autoUpdateCheck` getter + setter |
| `data/SettingsRepository.kt` | 新字段 `autoUpdateCheck`（默认 true）+ key + 读写 |
| `ui/common/UpdatePrompt.kt` | 重写：下载进度、安装按钮、失败原因，不再是「去蓝奏云」 |
| `MainActivity.kt` | 启动时按开关查一次 + 挂 `UpdatePrompt` |
| `AndroidManifest.xml` | `REQUEST_INSTALL_PACKAGES` |
| `res/xml/file_paths.xml` | 放开 `cache/updates` |

## 七、构建与测试

- 构建：`:app:compileDebugKotlin` / `:app:assembleDebug` **BUILD SUCCESSFUL**
- 设备：vivo V2452A / Android 16 / 1260×2800
- 已验证：
  - 设置首页四个新入口都在、都能打开、都能返回（截图 01）
  - 更新与版本：版本号 0.1.94-mod（266）、检查更新按钮、自动检查开关、「暂无可用更新」正确显示（截图 02）
  - 诊断与反馈：四个诊断按钮 + 反馈入口都在（截图 03）
  - 实验室：三个验证入口都在（截图 04）
  - 关于：只剩身份/出处/链接/隐私/许可（截图 05）
- 未验证（需要真的发一个 Release 才能测）：发现更新、下载进度、安装跳转、无 APK 的 Release。代码路径已写好，但**没有拿它当已完成**。

## 八、还没做的

- 没有发 Release，所以下载/安装链路只有代码、没有真机完整走通
- 自动检查目前跟着启动走（`autoUpdateCheck` 默认 true），没有做「每隔 N 天」的额外节流（有 24 小时节流）
- 原版主题下的样式没有单独截图（组件复用设置页现成的 Section/Chip，不涉及玻璃改动）
