# 紫雾手账 · 第一批 DSH 本机验收（0.1.93-mod）

验收人：DSH（本机）。日期：2026-10-19 22:10–22:45。
设备：荣耀 YAL-AL50，Android 10（API 29），1080×2340 @480dpi，真实用户数据（未清空、未卸载）。
基线：`feat/lavender-scrapbook-batch01` @ `b95e154`（相对 `04d41b2` 的 0.1.92 主开发线）。
交付：本分支仅把版本号推到 `0.1.93-mod` / `versionCode 265`，代码与 ASTER 交接一致。

## 构建与测试

本机环境（ASTER 遇到的是 Gradle 分发下载失败，不是代码问题）：

- JDK 21，Android SDK compileSdk 37，Gradle 9.7.1（本地已解压分发，未再下载）
- 命令：`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug`
- 结果：**BUILD SUCCESSFUL**；`compileDebugKotlin` 通过，`assembleDebug` 通过
- 单元测试：**150 个测试套件 / 1060 个测试，0 失败 0 错误 0 跳过**（含 `VisualThemeTest` 2 条）
- APK：91,792,985 字节，SHA-256 `0337980eabe69c8a1a8f6ed24a25bf59cfde69cffe3ccdc858152c499264628a`
- 签名：沿用 `dist/签名备份/cleos-test-signing-debug.keystore`（与设备已有安装同签名，可覆盖升级）

## 五种必验场景（真机实测）

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 全新安装默认原作者液态玻璃 | **通过** | `shots/09-fresh-no-settings-default.png`：移走 DataStore 后首次启动，主题为原版玻璃，新建的设置文件里没有 `mod_visual_theme` 键（`strings` 实证） |
| 2 | 升级保留原有主题、壁纸和气泡设置 | **通过** | 0.1.91 → 0.1.92 → 0.1.93 连续覆盖安装；`glass_mode=Auto`、`app_icon=default`、`my_bubble_theme=glass`、`wallpaper=4*2wallpaper-8aa1f759…jpg`、`wallpaper_dark=true` 全部保留；`shots/12-upgrade-0.1.93-keeps-lavender.png` |
| 3 | 设置 → 外观 → 手动选择紫雾手账 | **通过** | 外观页「界面主题」原版默认 / 紫雾手账·试用两项可点；`shots/02-theme-picker-lavender.png`；选中后全应用立即切换 |
| 4 | 重启后保持成功保存的主题选择 | **通过** | `am force-stop` 冷启动两次、以及 0.1.93 覆盖安装后重启，均为紫雾手账；`shots/07-restart-keeps-lavender.png` |
| 5 | 切回原版默认，恢复原有图标、导航、卡片、气泡和背景 | **通过** | 外观页切回「原版默认」后玻璃模糊、青色强调、原壁纸全部回来，与 `shots/01-default-0.1.93.png` 一致；`shots/08-back-to-default.png` |

## 导航与适配

- 四个入口全部可点：聊天 / 日记 / 待办 / 主页，切换正常，见 `shots/03..06`。
- 四个入口的图标、标签、选中态（淡紫纸片标记）与波浪连接带在实机上完整显示，未升高选中项。
- 返回：设置二级页 → 一级页 → 主页多次返回正常。
- 滚动：设置页、聊天、日记页面滚动正常。
- 系统字体 1.3×：导航标签不重叠、不截断，`shots/10-bigfont-1.3.png`。
- 320dp 窄屏（`wm size 960x2080`）：四入口完整、标签不溢出，`shots/11-narrow-320dp.png`；测试后已 `wm size reset`，字体缩放回到 1.0。

## 数据安全

- 全程未卸载应用、未清空真实数据；「全新安装」是用 `mv files/datastore` 模拟无设置首启，验证后原样移回并核对。
- 备份留档：`cleos-data-backup.tar`（97 个条目，含 `files/datastore`、`databases/cleos.db`）在本机 `work/lavender/`。
- 聊天记录、角色数据在三次升降级后仍然完整（聊天页内容与升级前一致）。

## 未完成 / 未验证

- **输入法弹出时导航不顶起、不遮挡输入框**：未实测。本次自动化点击输入框未成功聚焦，不能算验证过。
- 设置写入 IOException 时的回退提示：未实测。
- 备份导出/恢复含独立主题字段：未实测（仅静态确认字段存在）。
- 拼贴图标、撕纸气泡、聊天顶栏、多媒体排版：属第二/三批，未开始。
- 未做 7 入口（按交接意见保留 4 入口，不新增导航业务）。

## 结论

第一批（主题开关 + 手账导航 + 原版隔离）在真机通过五种场景验收；可以并入主开发线，但**尚未合并**，也**未把紫雾手账设为默认**。原版默认、手账主动启用这一条优先级最高的约束仍然成立。
