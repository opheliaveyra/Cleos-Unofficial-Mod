# 紫雾手账 · 视觉素材库 🎨

本目录仅服务于 **Cleos 非官方二改版** 的 `lavender_scrapbook` 主题，不替换原作者主题。

## 资源结构

- `backgrounds/`：主题固定背景
- `frames/`：手账贴纸式装饰框
- `icons/`：后续待补的功能及动态状态图标
- `bubbles/`：独立设计和验证的可拉伸聊天气泡
- `call/`：语音通话的按钮/状态装饰
- `previews/`：组合预览与小尺寸核对图
- `manifest.json`：资源名称、功能、来源与接入状态

## 第一批待入库素材

| 路径 | 用途 | 状态 |
| --- | --- | --- |
| `backgrounds/lavender_scrapbook_fixed_wallpaper.png` | 紫雾手账独占固定背景 | 本地原图待上传 |
| `frames/frame_soft_note_lavender.png` | 柔边便签式容器 | 本地原图待上传 |
| `frames/frame_torn_note_lavender.png` | 撕纸花边式容器 | 本地原图待上传 |
| `frames/frame_speech_note_lavender.png` | 对话形便签容器 | 本地原图待上传 |
| `frames/frame_postage_lavender.png` | 邮票压花边框容器 | 本地原图待上传 |

> **上传状态说明：** 本次先创建素材分支、目录规范和清单。原始 PNG 已整理在可下载的素材 ZIP 中；当前 GitHub 连接无法从本地容器直接上传 PNG，因此表中的图片**尚未提交到分支**。待将 ZIP 中相应路径的 PNG 上传到此分支后，把 `manifest.json` 的 `repository_status` 改为 `present`。

## 使用约定

1. 保留独立的透明 PNG 原图及纸张边缘半透明信息；不要给边框额外加背景底板。
2. 固定背景只在 `lavender_scrapbook` 激活时启用。切换其他主题时恢复用户原有背景设置。
3. 装饰框是**图形素材**，不是现成的 Android 9-patch。需要适配动态文字尺寸时，应先拆层/制作九宫格方案，避免直接拉伸四角装饰。
4. 图标与装饰框不得混用，更不应覆盖原版 Cleos 的资源。
5. 合并到功能开发分支前，检查透明度、Android 缩放、文本留白、无障碍对比度与资源引用。
6. 对于已在 `feat/lavender-scrapbook-acceptance` 使用的现有图标，不重复复制到本目录。

## 交接

后续津渡在接入时以 `manifest.json` 为准，记录 Android 资源路径和真实界面使用位置。素材分支用于独立审核；批准后通过 PR 进入相应开发分支。
