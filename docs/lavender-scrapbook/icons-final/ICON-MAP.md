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
