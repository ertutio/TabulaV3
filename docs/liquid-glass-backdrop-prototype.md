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
| 几何与回退决策 | `liquidglass/LiquidGlassBackdropPlan.kt` | 纯函数：采不采、采多大、平移多少、背景以什么尺寸重绘、Layout 子项契约；每个回退分支都有枚举原因 |
| 外观参数 | `liquidglass/LiquidGlassBackdropSpec.kt` | 折射带宽度、位移、色散、模糊；与 `PhysicalLiquidGlassConfig` 互不覆盖 |
| 背景宿主 | `liquidglass/LiquidGlassBackdrop.kt` 的 `LiquidGlassBackdropHost` | 铺满绘制一次背景，并把绘制入口与窗口矩形交给采样侧 |
| 玻璃侧采样 | 同文件 `LiquidGlassBackdropSampling` | 按窗口坐标对齐、以**宿主尺寸**局部重绘、串折射 shader、按形状裁切 |
| shader | `liquidglass/LiquidGlassBackdropShader.kt` | AGSL：圆角矩形 SDF + 折射带位移 + 色散 + 采样夹取 |
| 调试预览 | `liquidglass/LiquidGlassDebugPreview.kt`、`LiquidGlassDebugBackgrounds.kt` | 可移动高对比网格 / 合成照片 / **尺寸相关背景**；宿主缩放、形状切换、只改位移的对照 |
| 折射几何模型 | `app/src/test/.../LiquidGlassBackdropRefractionModel.kt` | 与 shader `main()` 同式的纯 Kotlin 实现，让"边缘动、中心不动、不越界"可以在 JVM 上逐点断言 |

层序：背景采样层插在最前，原有 9 层（背景模糊、AGSL 表面、噪点、内阴影、
内容、底部内高光、separator、顶部内侧暗边、rim light）相对顺序不变。
`glassChildLayout()` 把"层数 / 内容下标 / 背景下标"抽成可断言函数。

## 尺寸契约（核心）

背景 lambda 在宿主里看到的 `size` 是**宿主尺寸**。采样层里必须让它看到**同一个
尺寸**，否则所有依赖 `size` 的几何（圆形半径、尺寸相关渐变、等分图案、居中元素）
都会按玻璃尺寸重算——画面就不是"同一张背景平移"，而是"按玻璃尺寸重画了一张"。

因此：

- 采样图层尺寸 = 玻璃尺寸 + 2 × `paddingPx`（**不是**玻璃大小）；
- 图层在 Layout 里以 `(-paddingPx, -paddingPx)` 放置，于是与玻璃在屏幕上重合；
- 背景内容以 `sourceWidth × sourceHeight`（宿主尺寸）建立子 `DrawScope`，再整体
  平移 `(padding - originX, padding - originY)`；
- shader 的 `uLayerSize` / `uGlassOrigin` / `uGlassSize` 全部由**图层实际尺寸**
  推导，玻璃尺寸 = 图层 − 2 × pad，两套尺寸不会互相漂移。

判据（调试预览的"尺寸相关"背景）：同心圆半径取 `size.minDimension`，渐变端点取
`size.width/height`，竖条数取 `size.width`。若采样层用错尺寸，玻璃内外的圆曲率、
渐变分布、竖条密度会明显不连续。

## 采样外扩（边缘余量）

`backdropPaddingPx()` = `max(模糊半径, 位移) / 2`，上限 64px、下限 1px。

位移本身是向玻璃内部走的，不会越界；但效果链里的模糊核会在图层边缘取到图层外的
像素（`TileMode.CLAMP` 会复制边缘），边缘就变成"被拉长的边界色"而不是真实背景。
外扩半个核即可消除。外扩一旦越过背景来源边界，直接回退原有材质，不去采玻璃自身。

## Layout 子项契约（采样不可用时内容必须仍被正确测量）

`glassChildLayout(hasBackdropLayer)` 给出：子项个数、内容下标、背景下标。
`planGlassLayout(...)` 给出：每个子项的测量约束与摆放偏移。

- 内容层拿**父约束**（不是固定尺寸），`wrapContent` 才能拿到真实内容尺寸；
- 其余层拿内容的固定尺寸；
- 背景层拿 `内容 + 2*pad`，摆在 `(-pad,-pad)`。

**背景层永远只发射一个子项**，哪怕采样不成立（此时它画空）。早期版本在采样不可用时
提前 `return`，于是 Layout 用错的下标测量内容，`wrapContent` 塌成 0，界面上看不到
内容却不报错——"采样句柄非空"不等于"子项存在"，这个推断本身就是错的。

## 绘制失效

`LiquidGlassBackdropState.revision` 与背景 lambda 本身都是 Compose 状态，且都在
**绘制阶段被读取**：

- 换背景 / 移动图案 / 滚动容器偏移 → 调用方 `invalidateContent()` → 采样层这一笔
  绘制被标脏，重画一次；
- 静止时没人改版本 → 一次也不重画。

重画次数通过 `hostDrawCount` / `layerDrawCount` 暴露成**可观测指标**，并写进预览页的
`AUDIT` 行（`hostdraw=` / `layerdraw=`）。探针据此判断"到底刷新了几次"：静止时两次
dump 必须不变，换背景 / 移动图案 / 改尺寸后必须严格增加。**不用"两张截图相同"替代
刷新次数验证**——截图相同既证明不了没在重画，也证明不了该重画时重画了。

## 形状契约

只有"四角半径一致"的圆角矩形能由单个 `uCornerRadius` 精确表达：

| 形状 | 结论 |
| --- | --- |
| `RoundedCornerShape(12.dp)` | 可采样 |
| `CircleShape`（四角都是 50%） | 可采样，半径夹到短边一半 |
| `CutCornerShape` | 回退（`UNSUPPORTED_SHAPE`） |
| 只圆上面两角 / dp 与百分比混用 | 回退 |
| 任意 `Shape`（Path / GenericShape） | 回退 |

形状可采样性只看形状本身（`isBackdropShapeSupported`），与尺寸无关；尺寸只影响
半径夹取。

## 刷新策略

`BackdropEffectKey` 把几何（图层尺寸、外扩、对齐平移）、外观参数和背景内容版本
合成为一个可比较的键。键不变就不重建效果，静止画面没有额外开销。滚动时位置变了
必须重对齐，但重画的只是"玻璃大小 + 外扩"的小图层。

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
