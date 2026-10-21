# 拼贴手账 · 最终套件（38 图标 + 8 气泡 + 媒体/方向键）

切自 `sheets/O-38-8.png`（1536×1024，透明底）。

| 名称 | 编号 |
|---|---|
| home | f_01 |
| chat | f_02 |
| diary | f_03 |
| todo | f_04 |
| role | f_05 |
| file | f_06 |
| gallery | f_07 |
| camera | f_08 |
| music | f_09 |
| game | f_10 |
| world | f_11 |
| map | f_12 |
| calendar | f_13 |
| note | f_14 |
| favorite | f_15 |
| mail | f_16 |
| ai | f_17 |
| settings | f_18 |
| sync | f_19 |
| clock | f_20 |
| tag | f_21 |
| delete | f_22 |
| search | f_23 |
| notify | f_24 |
| voice | f_25 |
| attach | f_26 |
| star | f_27 |
| heart | f_28 |
| add | f_29 |
| more | f_30 |
| back | f_31 |
| forward | f_32 |
| play | f_33 |
| pause | f_34 |
| volume | f_35 |
| mute | f_36 |
| refresh | f_37 |
| close | f_38 |
| more_actions | f_39 |
| check | f_40 |
| info | f_41 |
| warning | f_42 |
| bubble_recv | f_43 |
| bubble_send | f_44 |
| bubble_typing | f_45 |
| bubble_voice | f_46 |
| bubble_image | f_47 |
| bubble_file | f_48 |
| bubble_reply | f_49 |
| bubble_system | f_50 |

## 头像框（2026-10-21 新增）

| 文件 | 资源 | 用途 | 用在哪 |
|---|---|---|---|
| `scrap_avatar_frame_human.png` | `R.drawable.scrap_avatar_frame_human` | 「我」的头像装饰框（淡紫、兔子、薰衣草、手账胶带） | 主页「我和 TA」左头像 |
| `scrap_avatar_frame_ai.png` | `R.drawable.scrap_avatar_frame_ai` | 「TA」的头像装饰框（雾蓝紫、黑猫、星月、星球） | 主页「我和 TA」右头像 |

- 素材：768×768 RGBA，中心透明区约 58%（human 447px / ai 436px），四周透明
- 渲染：`requiredSize(156.dp)` 溢出绘制、不参与布局；有框时头像用 90dp（无框仍 96dp）
- 只在 `VisualTheme.LAVENDER` 下出现，原生主题没有任何框
- 不替换、不裁剪用户头像；框只是盖在上面的装饰层，用户头像数据一个字节没动
- 来源：夸克网盘 `cleos/scrap_avatar_frame_*.png`（Aster 绘制）

## 头像之间的连接装饰（2026-10-21）

| 文件 | 资源 | 用途 | 用在哪 | 主题条件 |
|---|---|---|---|---|
| `scrap_avatar_connection_flower.png` | `R.drawable.scrap_avatar_connection_flower` | 两个头像之间的丝带花结（淡紫 + 雾蓝双色） | 主页「我和 TA」双头像之间 | 仅 `VisualTheme.LAVENDER`；其他主题仍是 `✧` 文字星星 |

- 素材：320×320 RGBA，透明背景，内容 238×210
- 渲染：`size(65.dp)`（素材内容只占 74%，视觉花结约 48dp，约等于头像框直径的 1/3）
- 垂直对齐：Row 是 Top 对齐，用 `align(CenterVertically)` + `offset(y = -12.dp)` 让花结对准头像圆心
- 不加底板、阴影、发光；和两侧头像框的丝带有轻微叠压 —— 这是设计要的效果（同 Aster 的效果图）

## 双头像整图（2026-10-21，当前方案）

| 文件 | 资源 | 用途 | 用在哪 | 主题条件 |
|---|---|---|---|---|
| `scrap_avatar_pair_combo.png` | `R.drawable.scrap_avatar_pair_combo` | **一整张**：左 Human 框 + 中间丝带花结 + 右 AI 框（Aster 效果图） | 主页「我和 TA」 | 仅 `VisualTheme.LAVENDER`；其他主题还是两列 Person + ✧ |

为什么用整图：框是从两侧往中间溢出的，中间再塞一朵花结，三块交界永远差一点；
一整张是作者画好的，对齐关系不用猜。比例全部来自原图测量（1983×793）：

| 量 | 原图 px | 比例 |
|---|---|---|
| 头像直径 | 488 | 24.609% 图宽 |
| 左圆心 x | 481 | 24.256% 图宽 |
| 右圆心 x | 1504 | 75.845% 图宽 |
| 圆心 y | 385 | 48.55% 图高 |
| 图高/宽 | 793/1983 | 0.3999 |

实现：`BoxWithConstraints` 按可用宽度缩放整图，两个头像用 `offset` 绝对定位到对应圆心。
名字行用两等分（圆心在 24% / 76%，等分中心 25% / 75%，对得上）。

> 备选素材 `scrap_avatar_frame_human/ai.png` 和 `scrap_avatar_connection_flower.png` 仍留在 `drawable-nodpi`，
> 分层方案（单框 + 花结分别渲染）的代码路径已移除，但素材留着，以后想换回去不用重新下载。
