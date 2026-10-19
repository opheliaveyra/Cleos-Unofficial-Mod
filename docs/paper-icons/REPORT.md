# Cleos 二改版：纸绘功能图标盘点与实施报告

基线：`feature/chat-file-upload-and-hold-to-talk`，提交 `4ae9bcd855c7767332b02049ebba68fec468617d`，版本 `0.1.91-mod`。施工分支 `feat/paper-functional-icons`。上游原作者仓库未修改。未改变版本号、签名、包名、桌面启动器选择或任何业务代码。

## 全量盘点范围与口径

扫描 Git 基线全部 701 个受跟踪文件的路径；逐个解析 496 个 Kotlin/Java/XML/HTML/CSS/JS/JSON/Gradle 文本文件。仓库有 402 个 Kotlin 文件（含测试与桌面端）；Android 主源码有 252 个 Kotlin 文件。资源目录内 127 个文件全部逐项列入清单。未用页面抽样推算。

| 项目 | 准确静态数量 |
|---|---:|
| Material 完整变体（包含 Rounded/Outlined/AutoMirrored） | 102 |
| Material 末级符号名 | 92 |
| Material 字面引用位置 | 236 |
| 新绘制独立纸绘矢量（含状态变体） | 41 |
| 新体系覆盖的 Material 符号名 | 56 |
| 映射到纸绘的字面引用位置 | 110 |
| 保留原图标的字面引用位置 | 126 |
| 按当前语义映射合并的功能组 | 77（41 纸绘、36 保留） |
| 原有资源文件 | 127，全部保留 |
| 启动器相关资源文件 | 116，全部保留（16 个可选项及原默认、多密度配套） |

“110”是静态可映射引用数，不是 110 个独立新图标，也不是运行时实际出现次数。统一组件会在红/绿语义色时回退原向量，实际展示数量随页面、状态、主题变化。一个定义可由多个动态列表/共用控件反复显示。

完整 CSV：

- [material-references.csv](material-references.csv)：每一处 Material 引用的名称、功能、类别、基线文件行号、源码行、替换决策与资源位置。
- [final-icons.csv](final-icons.csv)：按功能去重后的全部 77 组最终清单，包含新增/替换/保留与数量。
- [all-resources.csv](all-resources.csv)：全部 127 个资源文件及字面引用位置。
- [renderers-and-resources.csv](renderers-and-resources.csv)：全部资源、Icon/Image/AsyncImage、Painter/ImageVector、Canvas/draw、Unicode 图形字符等候选引用。
- [scanned-files.csv](scanned-files.csv)：逐个扫描的文件、大小和 Material 引用数量。
- [stats.json](stats.json)：机器可读数量。

扫描排除注释，保留字符串；逐行匹配，因此能覆盖弹窗、输入框、聊天工具栏、设置、状态提示及动态列表中的图标定义。`ImageVector` 主要用作参数类型/Material 向量传递，基线没有自定义 `ImageVector.Builder` 图标。Painter 自定义实现是动画贴纸（`Stickers.kt`）；资源 Painter 包含气泡装饰与启动器预览；均不是功能图标，不替换。桌面端使用文字按钮/角色 emoji，保留。

静态边界：动态加载的用户头像、贴纸、壁纸、聊天图片、第三方 HTML 卡片、运行时下载内容不能枚举为有限的内置图标数。Unicode emoji、Canvas 动态绘制、图片与类型名在候选表中列出，不冒充独立功能图标。这是完整源码词法盘点，不是已完成所有运行态的屏幕遍历。

## 视觉规范

24×24 viewport，默认 24dp；沿用现有控件指定的 16/18/19/20/24/26dp 尺寸与布局，不扩大点击区或改变留白布局。笔触 1.25 单位，圆端点与圆连接；少量不规则轮廓形成铅笔感。纸底 `#FFF9EF`，淡紫 `#DED4F1`，雾蓝 `#C9DEEA`，石墨紫 `#514960`。淡色填充 alpha 0.78，轻微纸纤维短线 0.3 单位、alpha 0.14，裁切在纸面内。纸纤维在小尺寸下允许消失，识别不能依赖纹理。

没有投影或 3D 透视，避免与现有液态玻璃的光向冲突。轮廓下有 2.3 单位暖白隔离线，帮助在深色及复杂壁纸上分离。主要轮廓/纸面的计算对比度约 8:1；这不代表任意壁纸整体对比度已通过实机验证。禁用透明度沿用原 tint alpha；红/绿语义状态保留原向量与 tint。喜欢/未喜欢、未读信件、添加图片/角色和已读聊天保留独立变体。发送保留 RTL 自动镜像。导航选中态沿用玻璃选中透镜、文字颜色和字重；纸绘图形本身保持一致。

不将总览图裁切为资源。每个矢量独立绘制，SVG 仅为可编辑设计源，APK 只使用 Kotlin ImageVector，无新依赖、位图、字体包或图片解码开销。

## 实际落地与回滚

`ui/icons/PaperIcon.kt` 为统一视觉适配器；30 个现有 Kotlin 文件只更换或补充 Icon import，包括共享 GlassIconButton 和 GlassTabBar 的图标绘制入口，完整覆盖通配 Material import。玻璃材质、渲染、导航、回调、路由、语义描述、按钮 Modifier、业务行为没有改动。

`ui/icons/PaperVectors.kt` 为生成的缓存向量注册表；`tools/paper-icons/catalog.py` 是独立路径和功能别名的唯一编辑源。`generate.py` 同时生成 Kotlin 与 `docs/paper-icons/svg/*.svg`；两者使用同一组路径与色彩。首次使用时构建 41 个缓存向量，以后不重复解析。

本仓库没有原版/二改版产品 flavor 分离；增加二改仓库构建开关 `BuildConfig.PAPER_ICONS_ENABLED`，默认开启，`./gradlew :app:assembleDebug -PpaperIcons=false` 恢复原 Material 绘制。没有更改原作者仓库，也没有宣称本仓库内存在一个原版 flavor。可直接撤销本施工分支。

保留：返回、关闭、添加、删除、搜索、展开/收起、菜单、警告、信息、锁、播放/暂停/停止、通话等 36 组通用/状态功能，保持小尺寸及危险操作辨识。删除、播放未强行插画化。

新增 41 个资源均用于替换已有功能，无新增产品功能。替换清单如下（路径为 `ui/icons/PaperVectors.kt#键` 和 `svg/键.svg`）：

| 资源键 | 功能 | 覆盖引用数 |
|---|---|---:|
| `add_image` | 添加图片 | 4 |
| `add_role` | 添加角色 | 1 |
| `alarm` | 闹钟/计时器 | 2 |
| `attachment` | 附件 | 1 |
| `book` | 世界书 | 2 |
| `bookmark` | 收藏书签 | 3 |
| `box` | 数据备份 | 1 |
| `calendar` | 日历 | 5 |
| `camera` | 相机 | 2 |
| `chat` | 聊天 | 11 |
| `cloud` | 天气 | 1 |
| `copy` | 复制 | 1 |
| `diary` | 日记 | 8 |
| `edit` | 编辑 | 2 |
| `favorite` | 喜欢 | 2 |
| `favorite_outline` | 尚未喜欢 | 1 |
| `feed` | 动态 | 3 |
| `file` | 文件 | 4 |
| `folder` | 文件夹 | 2 |
| `game` | 游戏 | 3 |
| `group` | 群聊 | 1 |
| `home` | 主页 | 2 |
| `image` | 图库 | 2 |
| `letter` | 信件 | 2 |
| `location` | 位置 | 1 |
| `memory` | 记忆 | 2 |
| `mic` | 语音输入 | 3 |
| `model` | 模型 | 4 |
| `music` | 音乐 | 2 |
| `palette` | 外观 | 1 |
| `read_chat` | 已读聊天 | 1 |
| `role` | 角色 | 2 |
| `send` | 发送 | 3 |
| `service` | 外部服务 | 4 |
| `settings` | 设置 | 1 |
| `spark` | 灵感 | 4 |
| `todo` | 待办 | 6 |
| `unread_letter` | 未读信件 | 1 |
| `voice` | 声音 | 3 |
| `widget` | 组件 | 1 |
| `world` | 联网/探索 | 5 |

## 验证结果与未完成项

已通过 `python3 tools/paper-icons/verify.py`：30 个既有 Kotlin 文件除 import 外逐行相同；127 个既有资源/Manifest 逐字节相同；41 个 SVG 均可解析，生成过程可重复；全部 Material Icon 绘制消费者含通配 import 已接入。检查了状态变体、RTL 与不覆盖 tint 的回退路径。执行 `git diff --check` 和 Python 语法检查。

本地尝试 `./gradlew :app:assembleDebug :app:testDebugUnitTest`：**阻塞，未进入编译**。Gradle 9.7.1 下载失败，`Network is unreachable`，原始日志见 [build-local.log](build-local.log)。本环境没有 Android SDK/adb/模拟器。不能将本地静态验证写成编译/运行通过。

新增 `.github/workflows/paper-icons.yml`：构建纸绘 APK、执行单元测试、编译关闭图标开关的配置，并在 API 35 模拟器中安装真正 APK，通过 UIAutomator 点击四个主导航与设置，保存真实页面 PNG/XML 为 `paper-icons-android-evidence`。截图脚本只允许一次性模拟器运行，找不到真实页面会失败，不生成假截图。工作流本身尚需成功运行后才能算验证通过。

**尚未完成交付验收**：Android 编译与单元测试、实际点击行为、真实页面截图、实际 16/19/24dp 可读性、深浅主题及纯黑/纯白/高饱和混合壁纸的运行态对比度、不同字体缩放与 TalkBack。未提供伪装成实机的布局图；真实页面截图必须来自已安装 APK。目前不得当成可发布 APK 合并验收。

## 桌面图标建议（独立事项）

保留现有 16 个选项。未来如需纸绘启动器，建议新增第 17 个可选项，独立设计 adaptive foreground/background 和多密度资源，再单独验收；本次未修改。

## 修改文件

完整文件列表见 [changed-files.txt](changed-files.txt)，包含 30 个图标 import 接入文件、构建开关、2 个绘制组件、41 个独立 SVG、全量 CSV 报告、审计/生成/验证/真实截图脚本及 CI。SVG 和报告不打入 APK。
