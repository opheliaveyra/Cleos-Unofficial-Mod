# 拼贴手账图标对照

切自总览图 50 枚（1536×1024，透明底）。

| 名称 | 原编号 |
|---|---|
| home | icon_01 |
| chat | icon_02 |
| book | icon_03 |
| checklist | icon_04 |
| people | icon_05 |
| folder | icon_06 |
| gallery | icon_07 |
| camera | icon_08 |
| vinyl | icon_09 |
| gamepad | icon_10 |
| planet | icon_11 |
| location | icon_12 |
| calendar | icon_13 |
| document | icon_14 |
| folder_heart | icon_15 |
| letter | icon_16 |
| robot | icon_17 |
| settings | icon_18 |
| cloud | icon_19 |
| alarm | icon_20 |
| bookmark | icon_21 |
| trash | icon_22 |
| search | icon_23 |
| bell | icon_24 |
| mic | icon_25 |
| clip | icon_26 |
| star | icon_27 |
| heart | icon_28 |
| plus | icon_29 |
| more | icon_30 |
| arrow_left | icon_31 |
| arrow_right | icon_32 |
| play | icon_33 |
| pause | icon_34 |
| volume | icon_35 |
| mute | icon_36 |
| refresh | icon_37 |
| close | icon_38 |
| chat_dots | icon_39 |
| check | icon_40 |
| info | icon_41 |
| warning | icon_42 |
| bubble | icon_43 |
| bubble_solid | icon_44 |
| bubble_dots | icon_45 |
| bubble_voice | icon_46 |
| bubble_image | icon_47 |
| bubble_file | icon_48 |
| bubble_input | icon_49 |
| bubble_info | icon_50 |

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

头像直径最终用 **27% 图宽**（原来是量到的水平透明段 24.609%，但那个尺寸头像和圆之间还有一圈缝；
垂直方向透明开口是 559/793，取中间值 27% 正好填满圆，只轻微压到框的纸质内缘）。

**绘制顺序**：整图**压在上面**（头像先画，框后画）。头像用 33% 图宽，多出来的部分被框的
不透明区域裁掉 —— 反过来让头像压框的话，头像一大就盖住兔子、黑猫那些装饰。
