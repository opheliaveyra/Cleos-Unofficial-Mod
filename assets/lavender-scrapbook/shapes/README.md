# shapes/ · 紫雾手账 UI 形状库

这一层放的是**基础视觉形状**，不是功能图标（图标在 `icons/`），也不是装饰框（`frames/`）。

## 命名规范

    <类别>_<形状>_<质感>.png

- 类别：`frame_`（容器类）/ `bubble_` / `divider_` / `arrow_` / `ring_` / `pattern_` / `shape_`
- 形状：`rectangle` / `round` / `capsule` / `circle` / `oval` / `triangle` / `diamond` / `star` / `heart` / `wave` / `grid` / `dot`
- 质感：`paper` / `lilac` / `cream` / `blue` / `pink` / `lace` / `stitch`

不放：imagegen 名、随机数字、临时导出名、无关旧图。

## 关于拉伸

**这批素材不是 9-patch。** 直接拉伸会把四角的花、丝带、纸边撕变形。接入时必须先判断：

| 类型 | 接入方式 |
|---|---|
| 矩形 / 圆角 / 胶囊 | 做九宫格（四角固定，中间拉） |
| 圆形 / 椭圆 / 星 / 心 / 三角 / 菱形 | 按固定尺寸用，不拉伸 |
| 分隔线 / 波浪 | 横向平铺（repeat） |
| 点阵 / 网格 | 平铺 |
| 环 / 圆环 | 旋转遮罩或切帧 |

## 当前状态

28 张 PNG **已入库**（`status: candidate`，`repository_status: present`）。
尺寸和透明通道见 `manifest.json` 每条的 `dimensions` / `transparent`。

这些是**候选**，还没接进任何界面；接入前必须先按上面的拉伸规则处理。原图在 `source/`，总览在 `previews/`。

完整的用途建议见开发分支 `docs/lavender-shapes/INTEGRATION-CANDIDATES.md`。
