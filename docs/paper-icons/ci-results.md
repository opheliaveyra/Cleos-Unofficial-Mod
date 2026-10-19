# Android 验证记录

本地：Gradle 9.7.1 下载因 `Network is unreachable` 失败，未进入编译。

GitHub Actions 第 1 次：https://github.com/opheliaveyra/Cleos-Unofficial-Mod/actions/runs/39196840986

- checkout / Java 配置成功。
- SDK 安装失败：`Warning: Failed to find package 'tools'`。
- 已修正 SDK 安装参数为 `packages: platform-tools`。

GitHub Actions 第 2 次：https://github.com/opheliaveyra/Cleos-Unofficial-Mod/actions/runs/39196911872

- checkout / Java / SDK 工具 / Gradle action 配置成功。
- 执行 `sdkmanager 'platforms;android-37'` 失败：`Warning: Failed to find package 'platforms;android-37'`。
- 这是当前仓库基线已有的 compileSdk 要求；本任务未改 SDK/AGP/Kotlin/Compose 版本。
- 编译、单测和模拟器截图全部被跳过；没有可展示的真实 APK 页面截图。
- 已扩大 SDK 搜索至 `--channel=3` 再试，后续结果以 PR Checks 为准。

静态检查通过不等于 Android 编译或点击测试通过。当前 PR 保持草稿。

GitHub Actions 第 3 次：https://github.com/opheliaveyra/Cleos-Unofficial-Mod/actions/runs/39197019053

- 加入 `--channel=3` 后仍在安装 Android 37 SDK 阶段失败；编译、单测、APK 页面截图均未执行。
- 当前阻塞未解决，PR 不具备发布验收条件。
