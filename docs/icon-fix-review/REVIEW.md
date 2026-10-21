# 图标修复分支验收 · fix/lavender-settings-icon-chrome

日期：2026-10-21。分支 `fix/lavender-settings-icon-chrome`（HEAD `a3f647c`）。
**没有合并、没有发布、没有改分支版本号。**

## 一、改动范围

只有 2 个提交、2 个文件、+11 −4 行：

| 文件 | 改了什么 |
|---|---|
| `ui/settings/SettingsPages.kt` | `Entry`：图标有拼贴素材时**不画圆形背景**，尺寸 34dp 容器里 19dp → 28dp；没有素材的保持原样 |
| `ui/home/HomeScreen.kt` | 小游戏 / 记忆 / 信箱三张卡片：有拼贴素材时图标 24dp → 32dp |

## 二、逐项验收

| # | 检查项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 设置页拼贴图标移除圆形背景 | ✅ 紫雾主题下所有 Entry 的淡紫圆底都没了，图标直接贴在纸面上 | 01 |
| 2 | 主页「小游戏」「记忆」「信箱」32dp | ✅ 三张卡片（加「收藏」共四张）图标清晰，**无挤压、无裁切、无错位** | 02 |
| 3 | 底部导航拼贴图标 + 选中态胶带 | ✅ 四个 tab 都是拼贴图标，选中 tab 上是浅色胶带块 | 02 |
| 4 | 只有紫雾用拼贴图标，原生主题不受影响 | ✅ 切到原版默认：圆形背景**还在**、图标是原生 Material、尺寸 19/24dp 不变 | 03 |
| 5 | 缺失图标回退原生 | ⚠️ **代码层面确认，未在真机触发**。见下 |
| 6 | 编译 | ✅ `assembleDebug` BUILD SUCCESSFUL（1 分 58 秒） | — |
| 7 | 实机 | ✅ 荣耀 YAL-AL50，装的是临时抬起版本号的构建（versionCode 270） | 01-04 |

## 三、主题隔离是怎么保证的

关键在 `usesScrapbookArtwork`：

```kotlin
internal fun usesScrapbookArtwork(icon: ImageVector): Boolean =
    LocalVisualTheme.current == VisualTheme.LAVENDER &&
        ScrapbookIconMap.byVectorName.containsKey(icon.name)
```

**先判主题再查映射** —— 原生主题下直接返回 false，圆形背景和原尺寸原样保留。
这个判断是这次改动能成立的前提，已确认存在且行为正确（截图 03）。

## 四、发现的问题

### 1. 图标边缘有毛边（既有问题，非本次引入）

设置页和主页的拼贴图标边缘能看到一圈深色描边残留，是抠图时留下的半透明边。
之前几个版本的图标就这样，**不是这个分支引入的**。要让 Aster 从素材那边处理。

### 2. 「未读邮件」和「已读邮件」用同一张图

映射表里 `MailOutline` 和 `MarkEmailUnread` 都指向 `scrap_mail`。
所以在紫雾主题下，有未读邮件时图标本身没有变化，**只能靠 tint 颜色区分**（未读 accentContent / 已读 contentSecondary）。
不算 bug，但视觉提示弱了。要么接受，要么让 Aster 补一张「未读」的素材。

### 3. 分支版本号落后主分支

分支基于 `978feb3`（0.1.96 / 268），主分支已经是 0.1.97 / 269。
**合并时版本号会被合并策略影响**，需要确认最终 versionCode 只增不减。

## 五、没做到的测试（不算通过）

| 项 | 为什么 |
|---|---|
| 缺失图标回退的真机验证 | 缺失的是 `ArrowUpward / ArrowDownward / ArrowDropDown / ExpandLess / ExpandMore / Stop`，这些都在需要特定交互的位置（生成中停止、折叠展开），没造出稳定场景。代码里 `CleosIcon` 的 fallback 逻辑是现成的，但**没有真机画面证据**。 |
| 小屏 / 大字体 / 长文案下的 32dp 表现 | 只在这台 1080×2340 上看过 |
| 深色模式 | 紫雾主题本身不跟深浅色，没测 |

## 六、结论

**建议合并。**

理由：
- 改动极小（11 行），只影响"有素材时"的尺寸和背景，没动布局和逻辑
- 主题隔离靠 `usesScrapbookArtwork`，原生主题实测没变
- 编译通过、真机显示正常

合并前要处理：
1. 确认合并后 versionCode 还是往上走的（分支落后一个版本号）
2. 「未读邮件」图标如果想有区分，需要 Aster 补素材
3. 图标毛边是素材问题，可以单独排期
