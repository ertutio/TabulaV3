# 液态玻璃背景采样层（原型）

本条开发线独立于 [#83 的功能开发](https://github.com/ertutio/TabulaV3/pull/83)，
从 `master` 单独开分支推进，不改变现有材质的高光、边缘、内阴影、噪点和形状。

## 目标

给 `PhysicalLiquidGlassBox` 增加一个**可选的背景采样层**：

- 同一个 Compose 图层提供背景，按玻璃区域对齐后采样；
- 边缘把背景"掰弯"（折射），前景文字与图标独立绘制、保持清晰；
- 调用方不提供背景时，画面与接入前完全一致；
- 离屏范围只覆盖玻璃节点 + 采样外扩，不逐帧把整张照片读回 Bitmap；
- 静止场景不因为新效果产生持续刷新。

## 分层

| 层 | 位置 | 职责 |
| --- | --- | --- |
| 几何与回退决策 | `liquidglass/LiquidGlassBackdropPlan.kt` | 纯函数，决定采不采、采多大、平移多少；每个回退分支都有枚举原因 |
| 外观参数 | `liquidglass/LiquidGlassBackdropSpec.kt` | 折射带宽度、位移、色散、模糊；与 `PhysicalLiquidGlassConfig` 互不覆盖 |
| 背景录制 | `liquidglass/LiquidGlassBackdrop.kt` 的 `LiquidGlassBackdropHost` | 把背景录进一个 `GraphicsLayer`，只录背景 |
| 玻璃侧采样 | 同文件 `LiquidGlassBackdropSampling` | 按窗口坐标对齐、平移进小图层、串折射 shader、按形状裁切 |
| shader | `liquidglass/LiquidGlassBackdropShader.kt` | AGSL：圆角矩形 SDF + 折射带位移 + 色散 + 采样夹取 |
| 调试预览 | `liquidglass/LiquidGlassDebugPreview.kt`、`LiquidGlassDebugBackgrounds.kt` | 可移动高对比网格 + 合成照片 + 折射开关 |

层序：背景采样层插在最前，原有 8 层（背景模糊、AGSL 表面、噪点、内阴影、
内容、底部内高光、separator、顶部内侧暗边/rim light）相对顺序不变。
`glassContentLayerIndex` 把这件事抽成可断言函数。

## 坐标对齐

玻璃左上角在窗口坐标系的矩形，减去背景来源的窗口矩形，得到玻璃在背景中的
原点 `(originX, originY)`。采样图层尺寸是"玻璃 + 2×padding"，背景在图层内
平移 `(padding - originX, padding - originY)`，玻璃内容固定落在 `(padding, padding)`。
因此：

- 滚动或改尺寸时原点变化 → 平移量跟着变化 → 不会错位；
- 平移量 + 原点恒等于 padding，长期滚动不会累积误差；
- 采样外扩一旦越过背景来源边界，直接回退原有材质，不去采玻璃自身或黑边。

## 刷新策略

`BackdropEffectKey` 把几何（图层尺寸、外扩、对齐平移）、外观参数和背景内容
版本合成为一个可比较的键。键不变就不重新录制采样层，静止画面没有额外开销。
滚动时位置变了必须重录，但重录的只是"玻璃大小"的小图层。

## 参考实现（按固定源码版本）

以下仅作为设计与写法参照，用于核对"背景与前景分离、坐标对齐、边缘采样、
降级路径"这几件事；**未逐行复制外部代码**，AGSL 为按本仓库需要自写。

| 仓库 | 提交 | 参考文件 | 关注点 |
| --- | --- | --- | --- |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | `896a94a` | `backdrop/src/main/java/com/kyant/backdrop/backdrops/LayerBackdropModifier.kt`、`backdrop/src/main/java/com/kyant/backdrop/DrawBackdropModifier.kt`、`backdrop/src/main/java/com/kyant/backdrop/effects/Lens.kt` | 背景记录时机、`GraphicsLayer` 的 `record`、`onGloballyPositioned` 坐标对齐、边缘采样与 padding 回退 |
| [chrisbanes/haze](https://github.com/chrisbanes/haze) | `a0398c5` | `haze-glass/src/commonMain/kotlin/dev/chrisbanes/haze/glass/GlassShaders.kt`、同目录 `RefractionProfile.kt`、`haze-glass/src/jvmTest/kotlin/dev/chrisbanes/haze/glass/EdgeRefractionShaderTest.kt` | 背景与前景分离、采样坐标夹取（`clampSampleExpression`）、折射边界与降级 |
| [QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView) | `d4238c5` | `core/src/main/res/raw/liquidglass_effect.agsl`、`core/src/main/java/com/qmdeve/liquidglass/impl/LiquidGlassimpl.java` | AGSL 原生效果链（`content.eval` 取样、折射带内位移、色散多采样）、`RenderNode` 侧的效果挂载顺序 |

许可证：Kyant0/AndroidLiquidGlass 与 chrisbanes/haze 为 Apache-2.0，
QmDeve/AndroidLiquidGlassView 为 MIT。本 PR 未复制其源码，因此未在源文件内
附带版权头；如后续确需直接复用代码片段，须在该文件保留原始版权、许可与
适用声明，并在 PR 说明中列明来源文件与提交。

## 本仓库已有实现（不复用其结构，仅沿用约定）

- `RealLiquidGlass.kt` 的 `createLiquidGlassEffect`：先模糊再折射的效果串联顺序，
  本原型沿用同样的顺序。
- `PhysicalLiquidGlass.kt` 的 `LIQUID_GLASS_SURFACE_SHADER`：玻璃表面（高光/环带/
  色散/alpha）。本原型不改动它，只在其下增加背景层。

## 验收与证据

见 PR 描述，包含：调试预览入口、测试结果、构建结果、提交 SHA、参考文件清单。
真机截图/录屏与滚动掉帧对照在设备可用的环境下补齐，未完成部分在 PR 中明确标注。
