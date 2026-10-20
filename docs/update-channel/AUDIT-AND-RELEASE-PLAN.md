# 检查更新与版本管理 · 审计、修复与发布链路

日期：2026-10-21。分支 `feat/lavender-scrapbook-local`。

## 一、审计结果

任务书里说的两个老问题是**真存在过**，但在做「设置页重组」那轮已经改掉了。现状：

| 任务书要求 | 现状 | 文件 |
|---|---|---|
| 只用二改版仓库 | ✅ `api.github.com/repos/opheliaveyra/Cleos-Unofficial-Mod/releases` | `Updates.kt:206` |
| 不跳原作者 Releases / 蓝奏云 | ✅ 代码里没有任何原作者地址 | `Updates.kt` |
| 跳过 draft / prerelease | ✅ `if (draft \|\| pre) return null` | `Updates.kt:85` |
| 兼容 `0.1.92-mod` 的版本比较 | ✅ `parts()` 按 . 切、非数字段剥离，`newer()` 逐段比 | `Updates.kt:60-68` |
| 无 Release / 网络失败 / 最新 / 有新版 | ✅ 四个 `UpdateResult` 分支 | `Updates.kt` / `UpdatePage.kt` |
| 下载进度 + 失败原因 | ✅ `InstallStep.Downloading(done,total)` / `Failed(reason)` | `Updates.kt` |
| 系统安装界面、不静默 | ✅ `Intent.ACTION_VIEW` + FileProvider，先检查「安装未知应用」权限 | `Updates.kt:329-349` |
| 包名 / 签名校验 | ✅ `getPackageArchiveInfo` + `apkContentsSigners` 比对 | `Updates.kt:312-325` |
| SHA-256 + 大小 | ✅ 用 GitHub Release asset 的 `digest` 字段 | `Updates.kt:293-311` |
| 每日自动检查、可关、不反复弹 | ✅ `shouldCheck` / `shouldPrompt` + `autoUpdateCheck` 开关 | `Updates.kt:113-116` |
| 「关于」保留署名，更新独立成页 | ✅ 关于页只留出处/许可；更新在 App 段单独一行 | `AppPages.kt` |

## 二、这次补的

| 补了什么 | 为什么 |
|---|---|
| **versionCode 降级防护** | 原来只比版本名。版本名是人写的，真正决定能不能覆盖安装的是 versionCode；比现在的低就直接说清楚，不等安装器报错。`Updates.kt` `verify()` |
| **「历史版本」入口** | 任务书要求页面上有历史版本入口。放在「当前版本」卡片里，点了开 GitHub Releases 列表页 |

## 三、场景测试（真机 vivo V2452A，0.1.95-mod / 267）

| 场景 | 结果 | 证据 |
|---|---|---|
| 无 Release | ✅ 「二改版的仓库还没有正式发布，暂无可用更新」，附「打开 Release 页面」 | 01 |
| 网络失败 | ✅ 「网络不可用，检查一下连接再试。」+「再试一次」 | 02 |
| 已是最新版 | ⏸ 需要有 Release 才能测 | — |
| 有新版本 | ⏸ 需要有 Release 才能测 | — |
| 下载 + 校验 + 安装 | ⏸ 需要有 Release 才能测 | — |
| 签名不匹配 | ⏸ 需要造一个签名不同的包才能测 | — |

**为什么后四个测不了**：仓库现在一个正式 Release 都没有，`/releases` 返回空数组，代码走的永远是 NoRelease 分支。
这不是实现问题，是「没有可测的输入」。

## 四、发布链路（怎么把 APK 发到 GitHub Releases）

### 0. 一次性准备

1. **确认签名**：现在用的是 debug 签名（`signingConfig = debug`）。要么固定用同一个 keystore 发所有版本，要么换成正式 keystore。
   - 一旦换 keystore，旧版就**覆盖装不上去**了，必须卸载重装 —— 所以要么现在换，要么一直用同一个。
   - 建议：**固定一个 keystore**，放进 `~/.dsh/work/keys/`，`build.gradle.kts` 里从环境变量读密码，keystore 不进仓库。
2. `versionCode` 每版 +1（现在 267，下一版 268），`versionName` 跟着改。

### 1. 构建

```
cd cleos-lavender
./gradlew :app:assembleRelease   # 或 assembleDebug，只要签名固定
```

产物：`app/build/outputs/apk/.../app-release.apk`，改名叫 `CLEOS-<版本>-mod.apk`。

### 2. 算校验值

```powershell
Get-FileHash CLEOS-0.1.96-mod.apk -Algorithm SHA256
```

### 3. 创建 Release（需要仓库管理权限）

两种方式：

**A. 网页（最简单，推荐）**
1. 打开 https://github.com/opheliaveyra/Cleos-Unofficial-Mod/releases/new
2. Tag：`v0.1.96`（tag 名里要能读出版本号）
3. Title：`0.1.96-mod`
4. 描述：更新内容（App 里会原样显示这段）
5. **勾选「This is a prerelease」不要勾**（勾了 App 会跳过）
6. 上传 `CLEOS-0.1.96-mod.apk` 作为附件
7. Publish release

**B. gh CLI（需要 token）**
```
gh release create v0.1.96 CLEOS-0.1.96-mod.apk \
  --repo opheliaveyra/Cleos-Unofficial-Mod \
  --title "0.1.96-mod" \
  --notes "这一版改了什么"
```

### 4. 哪些步骤必须仓库管理者做

我**做不了**这三件：
1. **创建 Release**（要 GitHub 写权限的 token，我手上没有，也不该有）
2. **上传 APK 附件**（同上）
3. **决定 keystore**（要不要换正式签名是你的决定，换了这个动作不可逆）

我能做的：构建、算哈希、写好 release notes 文本、把 APK 放到你能取到的位置。
你拿到之后按上面 A 走一遍就行，一分钟的事。

### 5. 发布后我会验的

Release 一发出来，我就能把剩下四个场景跑完：检查到新版本 → 下载 → 校验 → 调起安装器 → 覆盖安装。

## 五、需要你确认的一件事

**签名要不要固定成正式 keystore？**

现在 `build.gradle.kts` 用的是 debug 签名。debug 签名在不同机器上不一样，换台机器构建出来的包就装不上去。
- 如果只在你这台机器构建发布 → 现在这样也能用
- 如果以后换机器 / 换人构建 → 必须固定 keystore，否则老用户全部要卸载重装

这件事定了，发布链路才算真正闭环。
