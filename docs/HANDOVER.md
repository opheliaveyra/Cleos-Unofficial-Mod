# 交接书 · 紫雾手账 UI V1 接入

> 写给下一个窗口的自己。读完这份就能直接接着干。

---

## 一、项目与环境

| 项 | 值 |
|---|---|
| 工作目录 | `C:\Users\15536\.dsh\work\cleos-lavender` |
| 分支 | `feat/lavender-scrapbook-local` |
| 推送目标 | `git push mine HEAD:refs/heads/feat/lavender-scrapbook-acceptance` |
| 用户称呼 | 玖玖 |
| 交流 | **中文**，回答要短，不解释废话 |
| QQ 汇报 | **默认不发**。只有她说「发到 QQ」才发 |

### Gradle 构建环境（每次都要带全，否则跑不起来）

```powershell
$env:GRADLE_USER_HOME="C:\Users\15536\.dsh\work\gradle-home"
$env:TMP="C:\Users\15536\.dsh\work\tmp"
$env:TEMP="C:\Users\15536\.dsh\work\tmp"
$env:JAVA_HOME="C:\Users\15536\.dsh\tools\jdk\jdk-21.0.12.1+1"
$env:ANDROID_HOME="C:\Users\15536\.dsh\tools\android-sdk"
$env:ANDROID_SDK_HOME="C:\Users\15536\.dsh\work\android-user"
$env:LOCALAPPDATA="C:\Users\15536\.dsh\work\localappdata"
Set-Location "C:\Users\15536\.dsh\work\cleos-lavender"
& "C:\Users\15536\.dsh\work\gradle-home\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle.bat" @(":app:assembleDebug","--console=plain","-Pkotlin.compiler.execution.strategy=in-process")
```

增量 `assembleDebug` 约 25~90 秒。

### 测试设备

- 荣耀 `YAL-AL50`，adb serial `NDYDU19830006567`，1080×2340
- `adb` 全路径：`C:\Users\15536\.dsh\tools\android-sdk\platform-tools\adb.exe`
- **adb 坐标是物理像素**。看 preview 图（872×1890）换算：× 1.238

---

## 二、绝对规则（违反过的都吃过亏）

1. **不要 uninstall**。验证 APK 更新看 `dumpsys package` 的 versionCode 或 `install -r` 返回 Success。我 uninstall 过一次，清了 37 条消息（后来用备份救回来了）。
2. **版本号**：`versionCode` 只增不减；发版 +0.0.1；版本号不以 1 开头。
3. **不擅自发布 APK**。用户没说要发包就不发。
4. **不改签名**。keystore 在 `work/signing/cleos-test.keystore`，alias `androiddebugkey`，密码 `android`，证书 SHA-256 `aa665f898d22be54e6f80165a4f3c2630192acf2c49f86c4f8e53eb9068bcb55`。
5. **测试时临时改 versionCode**（为了覆盖安装），测试完必须从 `.bak` 还原，不能提交。
6. **默认主题不能动**。所有改动只走 `VisualTheme.LAVENDER` 分支。
7. **不要用 `adb uninstall`、不要清数据**。

---

## 三、素材在哪

| 素材包 | 本地路径 | 状态 |
|---|---|---|
| UI V1 33 件 | `Downloads\cleos_lavender_ui_v1_33_assets.zip` | 已导入为 `scrap_ui_*` |
| 接入候选 52 件 | `work\ui-v1-phase2\integration_candidates\` | 已导入为 `scrap_v1_*` |
| 图标总表（用户发的） | `work\lavender\` 和 attachments | 140 个纸绘图标参考 |
| shape 元素库 5.6M | **夸克网盘**，本地没有 | 含矩形/圆角/胶囊/圆形/箭头/星心/气泡/波浪/圆环/阵点/十字 |

**夸克网盘文件夹**：`Cleos ui图片素材`（不要进 `cleos` 那个，那是历史 APK）

已导入的素材都在 `app/src/main/res/drawable-nodpi/`：
- `scrap_*`：第一批纸绘图标（chat/home/todo/diary/check/...）
- `scrap_ui_*`：33 件 UI 素材
- `scrap_v1_*`：52 件接入候选（含 `*_blank` 无字底板）
- `scrap_check_v3.png`：从图标总表裁的对勾（96×96，白底已抠透明）

---

## 四、已经做完的

### 核心页面
- **主页**：标题纸签、双头像整图组合（`scrap_avatar_pair_combo`）、卡片虚线花边
- **聊天**：标题纸签 + 副标题（模型切换）、气泡换 `bubble_*_blank` 九宫格、输入栏换 `input_chat_blank` 九宫格、消息上下不被遮挡（P0 已修）
- **日记**：标题纸签、分类选中胶带 `diary_selected_tape` + 花朵 `diary_selected_flower`
- **待办**：标题纸签、输入栏 `input_todo_blank` 九宫格
- **设置页**：所有卡片纸面化、图标底座换成 `avatar_frame_default` 纸绘圆框

### 公共组件层（一处改，全站生效）
- `GlassSurface` → 紫雾下走 `scrapbookSurface`（118 处调用）
- `GlassButton` / `GlassIconButton` → 同上
- `GlassTopBar` → 所有标题纸面（22 处调用），有素材的用素材，没有的代码画
- `scrapbookSurface` → 暖白底 + 外框 + 内圈虚线花边

### 文字配色
- `scrapbookPalette()` 补了 `mineContent = Ink`（原来继承 `Color.White`，浅底上看不见）
- 对比度实测：气泡正文 10.44、正文 10.96、次要 6.27、强调 6.07，全部过 WCAG AA

### 导航栏
- 胶带可拖动：长按揭起 → 拖动 → 松手贴合切页
- 普通点击正常（手势挪到 Row 层，不再被覆盖层吞掉）

---

## 五、没做完的（按优先级）

1. **模型胶囊最后一步验证**
   - 当前：胶囊在输入栏**上沿外侧**，只在紫雾主题显示
   - 待办：确认点击能弹出模型菜单。之前 adb 点击没反应，可能 `modelChoices` 为空，或者菜单渲染在 `GlassTopBar` 的 `subtitleMenu` slot 里 anchor 有问题
   - 如果弹不出来，把 `DropdownMenu` 直接挂到胶囊这一层

2. **空状态插画**：日记/待办的空状态卡现在是纯文字。素材只有 `empty_diary_reference.png` / `empty_todo_reference.png`（参考图，带固定文字不能直接用），需要用代码重画花边和插画

3. **10 个按钮素材没换**：`button_add_circle` / `confirm` / `cancel` / `calendar` / `more` / `heart` / `star` / `edit` / `delete` / `next`

4. **角色选择器**：`selector_character_blank` 没接

5. **筛选标签**：对话记录的「私聊/群聊」、朋友圈的「全部/当前 TA」还是原生 Pill

6. **日记分类未选中态**：现在是透明，需要设计

7. **头像框状态**：`avatar_frame_selected/online/offline` 需要先定义「在线/离线」的数据来源

8. **9 件装饰件落点**：`bow_lavender` / `cat_sticker` / `flower_*` / `sparkles` / `tape_lavender` 用在哪要问用户

---

## 六、踩过的坑（别再踩）

| 坑 | 真相 |
|---|---|
| `reverseLayout` 会反转 `contentPadding` | **不会**。contentPadding 永远是物理方向，top 就是屏幕上面 |
| 卡片看起来像普通圆角矩形 | `scrapbookSurface` 原来只有外框，补内圈虚线才有纸感 |
| 气泡文字看不清 | `mineContent` 继承了 `Color.White`，浅底上没对比度 |
| 设置图标底部有黑字 | `avatar_frame_*` 五张素材**源图就带文字残留**，已清理纯黑像素；`avatar_frame_large` 还有 85 个灰边，需要 Aster 重新导出 |
| 模型菜单点不开 | 纸签分支漏画了 `subtitle` 和 `onSubtitleClick` |
| 输入栏塞文字按钮会挤 | 模型名 64dp/46dp 都试过，「发消息」必折行。只能用图标或放外面 |
| 图片底板的角落装饰被拉变形 | 用 `ScrapNinePatch` / `drawScrapNinePatch`，四角保原样 |

---

## 七、关键代码位置

| 功能 | 文件 |
|---|---|
| 纸面样式 + 九宫格 + 紫雾 palette | `ui/theme/LavenderScrapbook.kt` |
| 图标映射表 | `ui/theme/ScrapbookIcons.kt` |
| 公共卡片/按钮 | `glass/GlassComponents.kt` |
| 顶部标题栏 | `ui/common/GlassPage.kt` |
| 气泡九宫格 | `ui/chat/ScrapBubbles.kt` |
| 聊天气泡入口 | `ui/chat/BubbleThemes.kt`（`ChatBubbleSurface`） |
| 聊天页布局/输入栏 | `ui/chat/ChatScreen.kt`（`ChatInputBar` 约 2830 行，`LazyColumn` 约 1250 行） |
| 日记分类标签 | `ui/diary/DiaryListScreen.kt`（`DiaryFilters`） |
| 待办输入栏 | `ui/todo/TodoScreen.kt`（`AddBar`） |
| 设置图标底座 | `ui/settings/SettingsPages.kt`（`Entry`，约 406 行） |
| 导航栏胶带 | `ui/theme/LavenderScrapbook.kt`（`ScrapbookTabBar`） |

---

## 八、历史文档

全在 `docs/`：
- `lavender-ui-v1-phase2/INTEGRATION-REPORT.md` ← **最全的施工日志，17 轮**
- `lavender-ui-v1/PROGRESS.md`
- `lavender-ui-v1-phase2/asset_status.csv` ← 52 件素材的分类清单
- `lavender-avatar-frames/README.md`
- `lavender-nav-tape/README.md`

---

## 九、怎么继续

1. 先跑一次 `assembleDebug` 确认能编译
2. 读 `docs/lavender-ui-v1-phase2/INTEGRATION-REPORT.md` 最后几节，看最后一次改到哪
3. 按「五、没做完的」顺序往下做
4. 每改一处就编译 + 真机截图，不要攒
5. **不要 uninstall、不要发 QQ、不要发包**
