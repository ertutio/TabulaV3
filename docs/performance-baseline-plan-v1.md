# Tabula 模拟器性能基线 v1

基线应用提交：`474129ed4d5262befc8bdab0fbd60e4df81411f2`（PR #5 合并后的 master）。目标是为以后优化提供可重复的参考，不修改应用功能，也不要求用户操作手机。

## 执行计划

1. 在独立的基线工具 PR 中增加测试模块、数据生成器、GitHub Actions 与报告程序。应用源码及正式构建配置保持原样；CI 在临时工作树里叠加 profileable 基准构建和测试模块。
2. 构建保留 R8、资源裁剪、非 debuggable 的 benchmark APK；仅使用 CI 调试证书。应用进程和测量进程分离，运行期间不并行编译。
3. 新建 Android 16 / API 36、x86_64 Google APIs 模拟器，Pixel 6 配置、2 核、2 GB、1080×2400、420 dpi、SwiftShader，保留 1× 动画；固定编译为 Full AOT。
4. 自动生成并扫描 96 张 1280×1920 JPEG，保存每张 SHA-256、尺寸、时间及整组摘要。测试只使用这些合成照片。
5. 排除权限/须知/首次引导，固定深色主题、30 张批次、固定尺寸、HDR/实况检测开、液态玻璃/流体云/振动关。分别运行切牌和摸牌。照片顺序由现有随机推荐决定，因此使用同尺寸、同编码参数的照片限制顺序造成的成本差异，并保留可见照片序列。
6. 两个独立云端会话各跑：冷启动 10 次、热启动 10 次、两种卡片样式各 5 轮×12 次右滑、大图 5 轮×6 次开关、内存快照 5 轮。每个动作必须命中真实界面；先执行图片解码和照片总数检查，失败即不生成有效基线。
7. 导出 Macrobenchmark 原始 JSON、Perfetto trace、操作序列、内存快照、环境/包/源码摘要、CSV 与中文摘要。将冻结结果存入仓库，原始 trace 保存在 Actions 构建产物中。
8. 后续传入候选提交，可在同一模拟器对比基线和候选；A 会话先基线后候选，B 反向，降低顺序偏差。当前只跑基线，不合并/发布性能工具 PR。

## 指标与判读

- 启动主指标为 Android 测得的首帧显示耗时（TTID），报告中位数、P95、样本数及两会话差异。卡片节点等待只保证界面就绪，不冒充完全图片显示耗时。
- 帧指标记录 CPU frame duration、API 支持时的 frame overrun，保存每轮分布和完整样本；以每轮 P95 的中位数作比较参考，不把所有帧当独立实验。
- 内存为指定动作结束后的 Total PSS / Java Heap / Native Heap / Graphics 快照，最大快照值不称实际峰值。
- 冷启动指进程冷、应用缓存已清；不承诺宿主或系统文件缓存冷。热启动保留进程和缓存。
- 不以一次小差异判定提速。报告先给出 A/B 会话波动；未来在同场对比并重复看到超过噪声的变化，再判断收益。初次基线不设置“一定提升 X%”的门槛。
- 不测删除用户照片、外部分享应用、真实 HDR/实况、ColorOS 流体云、电池/发热或真机 GPU。普通 JPEG 下的 HDR/实况检测开关仍会触发现有检测路径。

## 环境与长期保存

所有依赖和 Actions 按版本/提交固定；环境报告记录宿主 CPU、Android fingerprint、系统镜像 revision、模拟器版本/build、屏幕/内存/动画/编译模式及数据摘要。首次成功后冻结模拟器 build。系统镜像或测量协议变化必须形成新版本，不能直接与旧冻结数字比较；同场基线/候选仍是更可靠的比较方式。

这是一份测量参考基线，不生成或安装 ART Baseline Profiles，不替换原发布签名。基准构建的 app/src/main 与指定应用提交完全一致。

参考：[Android Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)、[CI 测量边界](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci)、[模拟器 Action](https://github.com/ReactiveCircus/android-emulator-runner)。
