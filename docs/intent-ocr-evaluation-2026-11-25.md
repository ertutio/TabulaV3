# Tabula V3 本地中文 OCR 选型评估（#94）

评估日期：2026-11-25 ｜ 目标分支：`feature/intent-batch-ocr-2026-11-25`（worktree `D:\VIBEcoding\TabulaV3-intent94`）
评估对象：intent（意图排序）场景下的**本地中文 OCR**，仅用于给照片排优先级，不上传图片。

---

## 0. 结论速览

| 项 | 结论 |
| --- | --- |
| 默认推荐 | **Tesseract4Android 4.8.0 + `tessdata_fast/chi_sim`** |
| 备选 | **ML Kit Text Recognition v2 中文 bundled**（仅当项目愿意放弃「零遥测」并改写 LICENSE 措辞） |
| 不该选 | `chineseocr_lite`（GPL-2.0，与本项目自定义协议冲突） |
| 唯一硬阻塞 | ML Kit 无官方遥测 opt-out，与 LICENSE 第 6 条「不收集任何用户行为数据」直接冲突 |

---

## 1. 项目约束（本评估的判定基准）

### 1.1 LICENSE 的三条硬承诺

`LICENSE` 第 6 节「隐私与安全」原文（`LICENSE:50-58`）：

```
- 🔒 100% 本地处理，所有数据存储在用户设备本地
- 🙈 不收集任何用户行为数据
- 🛡️ 不上传任何照片或个人信息

任何修改版本也应遵守上述隐私承诺。
```

第一条管「图片不外传」，第二条管「零遥测」——这是两条**独立**的约束，本评估全程分开判定（见 §5.2）。

### 1.2 工程约束（实测自仓库配置）

| 项 | 值 | 来源 |
| --- | --- | --- |
| minSdk | **31** | `app/build.gradle.kts:29` |
| targetSdk / compileSdk | 36 / 36 | `app/build.gradle.kts:27,28` |
| Kotlin | 2.3.0 | `gradle/libs.versions.toml:6` |
| AGP / R8 | 8.9.1 / 8.13.19 | `gradle/libs.versions.toml:5`、`build.gradle.kts:5` |
| 已有 APK 基线 | **28,820,441 字节（27.49 MiB）** | 实测 `app/release/TabulaV3.3.3.apk` |
| 已声明权限 | 含 `android.permission.INTERNET` | `app/src/main/AndroidManifest.xml:18` |

`INTERNET` 已存在这一条很关键：ML Kit 遥测走 Play services 时不受本应用网络权限约束（见 §2.4），本项目也无法用「删掉网络权限」这条民间缓解手段。

### 1.3 尚未提交的现行改动（评估时已存在）

工作树里已有一版 Tesseract 集成，**本评估以它为对照而非从零选型**：

- `app/build.gradle.kts` 已加 `implementation(libs.tesseract4android)`
- `gradle/libs.versions.toml` 坐标 `com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0`
- `app/src/main/assets/ocr/chi_sim.traineddata`（2,469,156 字节，SHA-256 `a5fcb6f0…1e1f730`，与官方文件逐字节一致，实测）
- `app/src/main/java/com/tabula/v3/data/intent/TesseractTextRecognizer.kt`

另有一个**可选**云辅助 `DeepSeekTextAssist.kt`（默认 `textAssist: TextAssist? = null`，用户逐批次开启且需自备 Key，只发脱敏文本不发图片，端点 `https://api.deepseek.com`）。它与「100% 本地处理」存在措辞张力，属 #94 独立议题，本评估只在 §5.2 提示，不展开。

---

## 2. 候选一：Google ML Kit Text Recognition v2（中文）

### 2.1 许可证：ML Kit Terms of Service，不是 Apache-2.0

这一点在**构建产物里可以硬核对**，不必只信文档。`com.google.mlkit:text-recognition-chinese:16.0.1` 的 POM 自带许可声明：

```xml
<licenses><license>
  <name>ML Kit Terms of Service</name>
  <url>https://developers.google.com/ml-kit/terms</url>
  <distribution>repo</distribution>
</license></licenses>
```

（实测：`https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/16.0.1/text-recognition-chinese-16.0.1.pom`）

条款正文见 [ML Kit Terms & Privacy](https://developers.google.com/ml-kit/terms)。两条对本项目致命的原文：

> "ML Kit APIs run on-device. When using the ML Kit APIs, you may not reverse engineer or attempt to extract the source code or any related software…"

> "The ML Kit APIs also send metrics about the performance and utilization of the APIs in your app to Google."

即：**闭源 + 强制遥测**。它不是 Apache-2.0 开源库，把它当「开源依赖」记进第三方清单是错的。

### 2.2 是否离线：bundled 离线可用，unbundled 首次必须下载

官方 installation paths 页把三种安装路径并列，并给出选择依据（[ML Kit 模型安装路径](https://developers.google.com/ml-kit/tips/installation-paths)）：

> 「**捆绑**：模型在构建时静态链接到您的应用。」
> 「如果您优先考虑以下事项，请使用捆绑选项：应用安装后立即提供完整功能；应用安装后在没有网络连接的情况下功能是否正常。」

以及 unbundled 的失败语义，原文：

> 「如果您未启用在安装时下载模型的选项，也未请求显式下载，模型将在您首次运行该功能时下载。**在下载完成之前，推理请求会失败。**」

官方 Android 指南对两者给出量化对比（[使用机器学习套件识别图片中的文本](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)）：

| 功能 | 非捆绑库 | 捆绑库 |
| --- | --- | --- |
| 库名 | `com.google.android.gms:play-services-mlkit-text-recognition-chinese` | `com.google.mlkit:text-recognition-chinese` |
| 实现 | 模型通过 Google Play 服务动态下载 | 模型在构建时静态链接到应用 |
| 应用大小 | 每种脚本架构约 +260 KB | **每种脚本架构约 +4 MB** |
| 初始化时间 | 首次使用前可能需要等待模型下载完毕 | 模型可立即使用 |

结论：**只有 bundled 满足「安装即离线可用」**；unbundled 在首次（或模型被清理后）必须有网 + 有 Play services。对本项目「本地优先」定位，unbundled 不可接受。

### 2.3 模型与体积：实测数字与「约 4 MB」官方口径差一截

我在本机下载并解包了构件，全部字节数为实测：

| 构件 | 压缩体积 | 解包后 |
| --- | --- | --- |
| `text-recognition-chinese-16.0.1.aar` | **2,036,186 字节（1.94 MiB）** | **4,884,865 字节（4.66 MiB）** |
| ├ `assets/mlkit-google-ocr-models/**`（25 个文件） | 1,705,557 字节（1.63 MiB） | **2,490,310 字节（2.37 MiB）** |
| ├ `third_party_licenses.txt` | — | 2,382,265 字节 |
| └ `classes.jar` | — | 1,192 字节（纯壳） |
| `play-services-mlkit-text-recognition-chinese-16.0.1.aar` | **78,105 字节** | 798,386 字节（其中 791,244 是许可证文本，`classes.jar` 仅 3,135 字节） |

你给的 `2,036,186` 与 `78,105` 两个数字**完全正确**；`Hani_ctc/…/lstm_model.fb = 891,872` 也**精确命中**（见下）。

模型明细（`assets/mlkit-google-ocr-models/`，全部实测）：

| 文件 | 字节 |
| --- | --- |
| `gocr/gocr_models/line_recognition_legacy_mobile/Hani_ctc/optical/lstm_model.fb` | **891,872** |
| `taser/detector/rpn_text_detector_mobile_space_to_depth_quantized_mbv2_v1.tflite` | 340,760 |
| `gocr/gocr_models/line_recognition_legacy_mobile/tflite_langid.tflite` | 315,520 |
| `gocr/…/Latn_ctc/optical/lstm_model.fb` | 309,568 |
| `taser/segmenter/tflite_script_detector_0.3.lstm_model` | 161,448 |
| `gocr/layout/line_clustering_custom_ops/model.tflite` | 129,612 |
| `gocr/layout/line_splitting_custom_ops/model.tflite` | 114,384 |
| `gocr/…/Hani_ctc/optical/assets.extra/LabelMap.pb` | 70,403 |
| 其余 17 个（conv_model、binarypb、bincfg 等） | 合计约 157 KB |

**有一处需要纠正**：你写「模型合计约 1.6MB」，实测**未压缩是 2,490,310 字节（2.37 MiB）**，1.63 MiB 是它在 AAR 里 DEFLATE 压缩后的体积（我按 zlib-9 复算得 1,705,557，一致）。两个数都对，只是口径不同——写文档时别混用。

**更要紧的发现：真正的体积大头不在这个 AAR。** `text-recognition-chinese:16.0.1` 的 POM 会传递依赖 `com.google.mlkit:text-recognition-bundled-common:17.0.0`，它才是推理引擎：

| 构件 | AAR 体积 | 解包后 | 说明 |
| --- | --- | --- | --- |
| `text-recognition-bundled-common-17.0.0.aar` | **17,960,748 字节（17.13 MiB）** | 44,414,594 字节（42.36 MiB） | 4 个 ABI 的 `libmlkit_google_ocr_pipeline.so` |
| 同上，arm64-v8a 单 ABI | — | `.so` 11,064,544（10.55 MiB） | 压缩后约 4.19 MiB |
| 同上，armeabi-v7a | — | 6,781,940（6.47 MiB） | 压缩后约 3.34 MiB |
| 同上，x86 / x86_64 | — | 11,561,048 / 11,626,128 | 压缩后约 4.36 / 4.24 MiB |
| `com.google.mlkit:common:18.11.0` | 434,066 | 1,275,860 | — |
| `play-services-base:18.5.0` + `basement:18.4.0` | 439,602 + 286,283 | — | unbundled 路径亦需 |
| `play-services-mlkit-text-recognition-common:19.1.0` | 437,936 | 1,316,077 | — |

所以「bundled 约 +4 MB/架构」的官方说法**只覆盖了模型 assets**，没算 `.so`。按实测，bundled 中文 OCR 对单 ABI（arm64-v8a）的实际增量约 **4.19 + 1.63 ≈ 5.8 MiB 压缩态**；四 ABI 全带则显著更大（R8 不裁 native 库）。

对照本项目 27.49 MiB 的现有 APK，这是 **+21% 左右**的量级——不是能忽略的量。

### 2.4 数据采集：官方披露的字段清单，且无官方 opt-out

[为满足 Google Play 的数据披露要求做准备](https://developers.google.com/ml-kit/android-data-disclosure) 逐项列出「在所有功能中收集的数据」，与文本识别相关的原文：

| 数据 | ML Kit SDK 会收集 |
| --- | --- |
| 设备信息 | 设备信息（例如制造商、型号、操作系统版本和 build）以及可用的机器学习硬件加速器 |
| 应用信息 | 软件包名称和应用版本 |
| 设备或其他标识符 | 「不用于唯一标识用户或实体设备的**每次安装标识符**」 |
| 效果指标 | 性能指标（例如延迟时间） |
| API 配置 | API 配置（例如**图片格式和分辨率**） |
| 特征输入和输出大小 | 用于诊断和使用情况分析的输入和输出大小 |
| 功能版本 | 功能版本 |
| 活动类型 | 事件类型（例如功能初始化、模型下载、检测、资源释放） |
| 错误代码 | 功能事件（例如功能初始化、模型下载、检测、资源释放）的错误代码 |

页面另注明：传输用 HTTPS 加密，且「不会将这些数据传输给第三方」。

**注意 `API 配置（例如图片格式和分辨率）` 这一项**：它不传图像内容，但会把「这张图多大、什么编码」报出去。对「不上传任何照片」这条承诺，技术上不构成上传照片；对「不收集任何用户行为数据」这条，它就是收集。

**关于 opt-out**：查不到任何官方开关。旁证两条：

- [googlesamples/mlkit issue #593「Add ability to disable Google MLKit metrics upload」](https://github.com/googlesamples/mlkit/issues/593)：2022-12-02 由 `shobanasuresh` 提出，理由正是「For privacy reasons, we're not allowed to gather device information from our customer devices」，已指派给 `bcdj`，**至今仍为 Open，无官方答复**。
- [googlesamples/mlkit issue #1076](https://github.com/googlesamples/mlkit/issues/1076)：2026-09-25 提出，标题即「Data Safety clarification for bundled ML Kit with no network permissions and ClientTelemetry connection24」，**至今 Open，只有社区回复，无 Google 官方澄清**。

### 2.5 issue #1076 的实测观察（读原文，不外推）

提出者的环境与结论（原文摘录）：

> "Our release manifest omits both INTERNET and ACCESS_NETWORK_STATE. There is no ad SDK, custom/remote model configuration, auto zoom, or application backend."

> "On an authorized Android16 device with Play services 26.34.36, the exact signed release APK creates a local `ml_sdk_instance_id`. **Recorded app-UID traffic was 0** and the DataTransport event table was empty. SDK scheduling reports a missing ACCESS_NETWORK_STATE permission. The live ClientTelemetry client returned **ConnectionResult 24 / API_DISABLED_FOR_CONNECTION**."

> "**This is not proof that other connections or devices cannot transmit.** Observation used same-signature Android instrumentation, so its execution-context limitations must be considered."

> "We are not treating the disabled connection as a supported telemetry opt-out."

对本文档的可用性判定，按证据强度分三层：

1. **可引用的事实**：在「无 INTERNET + 无 ACCESS_NETWORK_STATE」的 APK 上，应用 UID 的网络流量为 0，ClientTelemetry 返回 `API_DISABLED_FOR_CONNECTION`。
2. **不可外推的部分**：作者自己声明「不是其他连接或设备不会传输的证据」，也明确说「不是受支持的 opt-out」。2026-10-24 的跟帖（`Deemanta`）补充了同样保留：「We are not treating successful offline OCR, missing app network permissions, or an inconclusive emulator trace as proof that no later SDK transmission can occur.」
3. **对本项目的适用性：直接失效**。该缓解手段依赖「移除两个网络权限」，而本项目 `AndroidManifest.xml:18` 已声明 `INTERNET`（用于更新检查与安装）。**这条路本项目走不了。**

因此本项目若要采用 ML Kit，只能接受「遥测在场」，不能宣称零遥测。

### 2.6 minSdk：官方文档自相矛盾，AAR 清单给出第三个答案

三处说法不一致，全部实测：

| 来源 | 表述 |
| --- | --- |
| [文字识别 v2 概览页](https://developers.google.com/ml-kit/vision/text-recognition/v2) | 未标 API 级别 |
| [Android 指南页 页首摘要](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) | "…on Android devices running **API level 21** or higher." |
| 同上页正文「准备工作」 | "This API requires Android **API level 23** or above. Make sure that your app's build file uses a `minSdkVersion` value of **23** or higher." |
| `text-recognition-chinese-16.0.1.aar` → `AndroidManifest.xml` | `<uses-sdk android:minSdkVersion="21" />` |
| `play-services-mlkit-text-recognition-chinese-16.0.1.aar` → `AndroidManifest.xml` | `<uses-sdk android:minSdkVersion="21" />` |

你记的「官方文档写 API 21+」对页首摘要和两个 AAR 清单成立；但**正文要求 23**。本项目 minSdk 31，两者都不构成阻塞——不过文档里不能只写 21+ 就算核实完毕。

### 2.7 版本与维护状态

| 构件 | 最新版 | 发布日期 | 状态 |
| --- | --- | --- | --- |
| `com.google.mlkit:text-recognition-chinese`（bundled） | **16.0.1** | 2024-08-07 | 已约 2 年无更新 |
| `com.google.android.gms:play-services-mlkit-text-recognition-chinese`（unbundled） | **16.0.2** | 2026-10-26 | 2026-10 刚重建 |

（实测 `maven-metadata.xml` + [ML Kit 版本说明](https://developers.google.com/ml-kit/release-notes)。说明里对该次更新的描述：「On Android, rebuilt the Text Recognition v2 SDKs that run in Google Play services… There are no API changes.」）

即**只有 unbundled 在跟进维护**，bundled 停在 2024-08。对「安装即离线」的需求，这构成一个中期风险：修 bug 只能等 bundled 发版。

---

## 3. 候选二：Tesseract4Android 4.8.0 + `tessdata_fast/chi_sim`

### 3.1 许可证：代码与模型都是 Apache-2.0（已逐项验证）

| 对象 | 许可证 | 验证方式 |
| --- | --- | --- |
| Tesseract4Android 代码 | **Apache-2.0** | 仓库页头部标注 `Apache-2.0 license`；README 内嵌 `Copyright 2019 Adaptech s.r.o., Robert Pösel / Licensed under the Apache License, Version 2.0`（[adaptech-cz/Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android)） |
| 上游 Tesseract OCR | Apache-2.0 | 实拉 `tesseract-ocr/tesseract` 的 `LICENSE`，正文为 Apache License 2.0 |
| `tessdata_fast` 模型 | **Apache-2.0** | 实拉 `tesseract-ocr/tessdata_fast` 的 `LICENSE`，正文为 Apache License 2.0 |

README 声明的捆绑版本：Tesseract OCR **5.5.1**、Leptonica 1.85.0、libjpeg v9f、libpng 1.6.48，各自带自己的许可证。全部为宽松许可，**与本项目「禁止商业使用」的自定义协议无冲突**（Apache-2.0 不要求下游开源，只要求保留声明与 NOTICE）。

### 3.2 完全离线、无遥测

这条是**从代码路径判定**，不是从宣传语判定：

- AAR 的 `AndroidManifest.xml` 只有 `<uses-sdk android:minSdkVersion="21" />`，**不声明任何权限、不声明任何 service/provider/receiver**。
- `classes.jar` 内为 `com.googlecode.leptonica.android.*`、`com.googlecode.tesseract.android.*`、`cz.adaptech.tesseract4android.*`，纯 JNI 封装。
- POM 唯一依赖是 `androidx.annotation:annotation:1.3.0`。

没有任何网络组件、没有 Play services 依赖、没有 telemetry client。这满足 LICENSE 第 6 条的**全部三条**（本地处理 + 不收集行为数据 + 不上传）。

### 3.3 模型必须随 APK 打包

官方 README 的前置条件原文：

> "A **v4.0.0 trained data file(s)** for language(s) you want to use. These files must be placed in the (sub)directory named `tessdata` and the path must be readable by the app. **When targeting API >=29, only suitable places for this are app's private directories** (like `context.getFilesDir()` or `context.getExternalFilesDir()`)."

即：模型不能从 assets 直接读，必须**先解压到私有目录**。README 还提示了由此带来的空间代价：

> "It uses sample image and english traineddata, which are extracted from the assets in the APK to app's private directory on device. This is simple, but **you are keeping 2 instances of the data file** (first is kept in the APK file itself, second is kept on the storage) - wasting some space."

对本项目：APK 内 2.36 MiB + 首次解压后私有目录再 2.36 MiB，**首次启动写盘 2.4 MB**。工作树里的 `TesseractTextRecognizer.kt` 已按此实现（「首次使用只做一次本地解压，不联网下载」），逻辑正确。

`chi_sim.traineddata` 实测：**2,469,156 字节**，SHA-256 `a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730`，与官方 `tessdata_fast/chi_sim.traineddata` 逐字节一致（`Content-Length` 与哈希双重核对）。仓库已附 `.sha256` 校验文件，做法正确。

### 3.4 ABI 与每 ABI 原生库体积（已从 JitPack 构件实测）

**先纠一个坐标坑**：根聚合坐标 `com.github.adaptech-cz:Tesseract4Android:4.8.0` 的 POM 会同时拉入 **standard 和 openmp 两个变体**：

```xml
<dependency><groupId>com.github.adaptech-cz.Tesseract4Android</groupId>
  <artifactId>tesseract4android</artifactId><version>4.8.0</version><type>aar</type></dependency>
<dependency><groupId>com.github.adaptech-cz.Tesseract4Android</groupId>
  <artifactId>tesseract4android-openmp</artifactId><version>4.8.0</version><type>aar</type></dependency>
```

两项合计 26.9 MB（12,832,786 + 14,063,573），**体积翻倍且功能重复**。必须用模块坐标 `…Tesseract4Android:tesseract4android:4.8.0`（你给的备选坐标是对的，仓库现有 `libs.versions.toml` 用的也正是它）。

`tesseract4android-4.8.0.aar`（**12,832,786 字节**）实测：

| ABI | 原生库解包合计 | 明细 | 压缩后估算 |
| --- | --- | --- | --- |
| `arm64-v8a` | **7,514,856（7.17 MiB）** | libtesseract 4,152,872 / libleptonica 2,878,240 / libjpeg 251,888 / libpngx 231,856 | ≈ 3.01 MiB |
| `armeabi-v7a` | 5,522,488（5.27 MiB） | 3,002,448 / 2,160,740 / 187,096 / 172,204 | ≈ 2.75 MiB |
| `x86` | 7,811,284（7.45 MiB） | 4,323,340 / 2,969,648 / 271,936 / 246,360 | ≈ 3.17 MiB |
| `x86_64` | 7,964,336（7.60 MiB） | 4,334,632 / 3,104,384 / 281,728 / 243,592 | ≈ 3.22 MiB |
| 四 ABI 合计 | 28,812,964（27.48 MiB） | — | ≈ 12.15 MiB |

`classes.jar` 仅 49,418 字节；`AndroidManifest.xml` 只有 minSdk 一行。

顺带核实版本可用性（JitPack API + 直连构件）：

| 坐标 | 4.8.0 | 4.9.0 |
| --- | --- | --- |
| `com.github.adaptech-cz.Tesseract4Android:tesseract4android` | ✅ 200，12,832,786 字节 | ❌ **404**（JitPack 构建状态 `Error`） |
| `cz.adaptech.tesseract4android:tesseract4android` | ✅ 200 | ✅ 200，12,848,454 字节 |

README 里写的示例坐标是新的 `cz.adaptech…`（4.9.0）；老坐标 4.9.0 构建失败。**仓库现用 4.8.0 + 老坐标 = 当前唯一同时可用的组合**，但要记一笔：升级路径需要换坐标。

### 3.5 minSdk

- AAR 清单：`<uses-sdk android:minSdkVersion="21" />`（实测）
- README 前置条件：`Android 5.0 (API 21) or higher`

本项目 minSdk 31，无阻塞。

### 3.6 中文识别质量：有公开测评，Tesseract 不进前三；本项目场景缺量化数据

**能找到的可引用公开对比**：[中国电子信息测试服务平台《AI测评｜十二款开源OCR开箱测评，文字识别哪家强》](https://www.ceietn.com/a/zhengcefagui/guojiaji/930.html)（2024-06-20，测 Tesseract **V5.3.4**、PaddleOCR V2.7.5 等 12 款）。要点原文：

> 「印刷中文识别准确度测试中，综合前三分别是 **RapidOCR、RagFlow 和 Surya**。」（Tesseract 不在前三）
> 「在各种变形字体（艺术字、验证码等非标准字体）场景下…**识别准确度均较低**，如需提高变形字体的准确率需要针对变形字体进行专项训练。」
> 「在手写中文识别场景下，综合前三分别是 RapidOCR、ChineseOCR 和 RagFlow。」

具体分项数值在页面内是图片（`uploads/allimg/240620/*.jpg`），**未取到可引用的文本数字**——所以上面只能引「排名 + 定性结论」，不能引百分点。

模型档次的官方定性（`tessdata` 仓库维护者说明，见 [tessdata issue #148](https://github.com/tesseract-ocr/tessdata/issues/148) 引用的官方对比表）：

> `tessdata_fast`：Integerized LSTM of a smaller network than tessdata-best，**速度最快、准确率最低**（"Fastest / Least accurate"）
> `tessdata_best`：LSTM only，**最慢、最准**

即本项目选的 `chi_sim`（fast 档）是**精度最低的那一档**，这是刻意用精度换体积与速度。

中文误识的真实案例（可用于设定预期）：[tesseract issue #1624「Tesseract 4.0.0 beta fails to recognize Chinese text」](https://github.com/tesseract-ocr/tesseract/issues/1624)，用 `tessdata_fast` + `--psm 7 -l chi_tra`，期望「只要各位董事全力支持增資」，实际输出「由條生訪中克己」；维护者回复指出问题在大字号导致**行切分错误**，把图片从 672×70 缩到 384×40 后结果明显改善（`chi_sim` + psm 3 得到「只 要 各 位 董事 全 力 支 持 增资」）。

**对截图场景的启示（推断，非引用）**：Tesseract 对**行切分和字号**敏感，而 App 截图的典型特征是「小字、高分屏、行间距紧凑、深浅色主题混合」。这类图恰恰是它需要调 `psm` 和预缩放的区间。

**必须明确写下的缺口**：**没有找到针对「中文 App 截图 / UI 文本」的公开量化对比**。上面的测评用的是印刷文档、街景、手写、艺术字四类数据集，都不是 UI 截图。分类准确率（本项目实际只用「优惠券 / 新闻 / 分享页 / 验证码 / 活动提醒 / 订单票据」六类排序信号）更是完全无公开数据。**这一项只能自行实测**（见 §6）。

---

## 4. 候选三：PaddleOCR / Paddle-Lite

### 4.1 许可证

| 对象 | 许可证 | 验证方式 |
| --- | --- | --- |
| PaddleOCR | **Apache-2.0** | 实拉仓库 `LICENSE`，正文为 Apache License 2.0（[PaddlePaddle/PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)） |
| Paddle-Lite | **Apache-2.0** | 实拉 `LICENSE`，`Copyright (c) 2016 PaddlePaddle Authors` + Apache License 2.0（[PaddlePaddle/Paddle-Lite](https://github.com/PaddlePaddle/Paddle-Lite)） |

### 4.2 模型体积量级

官方文档的模型表（[PaddleOCR 文本识别模块文档](https://www.paddleocr.ai/latest/en/version3.x/module_usage/text_recognition.html)）：

| 模型 | 中文准确率 | 模型体积 | CPU 推理（常规/高性能） |
| --- | --- | --- | --- |
| `PP-OCRv5_mobile_rec` | **81.29%** | **16 MB** | 21.20 / 5.32 ms |
| `PP-OCRv5_server_rec` | 86.38% | 81 MB | 31.21 / 31.21 ms |
| `PP-OCRv6_tiny_rec` | 73.5%* | **4.4 MB** | — |
| `PP-OCRv6_small_rec` | 81.3%* | 20.4 MB | — |
| `PP-OCRv6_medium_rec` | 83.2%* | 73.3 MB | — |
| `PP-OCRv4_mobile_rec` | 78.74% | 10.5 MB | 17.48 / 3.61 ms |

（`*` 为官方在表中标注的多语言综合口径，非单中文。）

注意上表**只是识别（rec）模型**。PaddleOCR 是检测 + 识别两段式流水线，实际还要加检测模型（`PP-OCRv5_mobile_det` 等，同为 MB 级），再加 Paddle-Lite 推理引擎本体（多 ABI `.so`）。**端到端增量大于表中任一单值**。

### 4.3 集成复杂度与维护成本：显著高于前两者

- **不是 drop-in 库**。Paddle-Lite 需要 `.nb` 格式的优化后模型（用 `opt` 工具转换），不能直接用 `.pdmodel`；官方 Android demo 是工程模板而非 aar 依赖。
- 需要自己处理：检测框后处理、方向分类、透视变换（截图里一般可省）、以及 `.nb` 模型的 assets 打包与解压。
- 版本节奏快（v4 → v5 → v6 在两年内），模型与引擎版本需成对匹配。
- 官方 Android 部署文档以 CMake 集成 + demo 编译为主，对 Compose/Gradle 项目是额外工程。

### 4.4 是否需要首次下载

**不需要**：`.nb` 模型可以打进 assets，与 Tesseract 同法。这一点 Paddle 优于 ML Kit unbundled。

### 4.5 中文质量口碑（带来源限定）

- 上表 81.29%（mobile rec）是**官方自评**，不是第三方横评，只能作量级参考。
- 第三方横评中 PaddleOCR 在**街景**场景进前三（「前三名分别是 RagFlow、RapidOCR 和 PaddleOCR…均略高于 70%」），在**印刷中文**未进前三（[同一测评](https://www.ceietn.com/a/zhengcefagui/guojiaji/930.html)）。
- 结论：口碑上 PaddleOCR ≳ Tesseract（尤其复杂版式），但**本项目只需分类置信度级别的文字线索**，这个质量优势未必能兑换成排序收益。

**判定**：质量最好、体积与工程量也最大。作为「可选第三方案」保留，不建议首发采用。

---

## 5. 对照表与结论

### 5.1 对照表

| 方案 | 许可证 | 中文能力 | 首次下载 | 离线 | APK + 模型增量（单 ABI 压缩态，实测/估算） | minSdk | 性能 | 维护成本 | 关键风险 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **ML Kit 中文 bundled**<br>`com.google.mlkit:text-recognition-chinese:16.0.1` | **ML Kit ToS**（闭源，非 Apache） | 官方 marks `zh` 为 **Supported**（Hans/Hant/HK），定期评估 | **不需要** | ✅ 安装即离线 | ≈ **5.8 MiB**（`.so` 4.19 + assets 1.63，arm64）<br>四 ABI 更大；另有 17.9 MB 的 `bundled-common` AAR 传递依赖 | 文档 21+ / 正文 23+ / AAR 清单 21 | 官方：「拉丁语脚本可实时，其他脚本较慢」；中文属后者 | 低（两个坐标 + 一行 init） | **强制遥测且无 opt-out** → 违反 LICENSE 第 6 条；bundled 停在 2024-08 |
| **ML Kit 中文 unbundled**<br>`play-services-mlkit-text-recognition-chinese:16.0.2` | 同上 | 同上 | **需要**（经 Play services，首次失败即无结果） | ❌ 首次/模型被清后不离线 | AAR 仅 78,105 字节；模型存于 GMS | 同上 | 同上 + 首次等待下载 | 中（要处理下载等待/失败态） | 同上 + **依赖 GMS 存在**；无 GMS 设备不可用 |
| **Tesseract4Android 4.8.0 + `tessdata_fast/chi_sim`**<br>`…Tesseract4Android:tesseract4android:4.8.0` | **Apache-2.0**（代码与模型同） | 印刷中文可用；变形字体/艺术字弱；行切分与字号敏感 | **不需要**（assets 打包 + 一次本地解压） | ✅ **完全离线、零遥测**（无权限、无网络组件） | ≈ **4.65 MiB**（`.so` 3.01 + traineddata 1.64；arm64）<br>四 ABI ≈ 12.15 + 1.64 | AAR 清单 21 / README 21 | 纯 CPU、单线程（Standard 变体）；初始化成本高，需复用实例 | 低-中（JNI 封装稳定；升级需换坐标） | 精度最低档模型；**中文截图为无公开量化数据**；首次解压 2.4 MB 写盘 |
| PaddleOCR（PP-OCRv5_mobile）+ Paddle-Lite | **Apache-2.0**（两者） | 最强（mobile rec 官方自评 81.29% 中文） | **不需要** | ✅ 离线 | rec 16 MB + det（MB 级）+ 引擎 `.so`；**端到端最大** | 需按 Paddle-Lite 支持矩阵 | CPU 21.2 ms/图（桌面级实测，非手机） | **高**（`.nb` 转换、两段式后处理、CMake 集成、版本配对） | 工程量与体积远超需求；版本迭代快 |
| `chineseocr_lite`（ncnn/mnn/tnn） | **GPL-2.0** | 轻量中文 OCR，模型合计 4.7 MB | 不需要 | ✅ 离线 | dbnet 1.8M + crnn 2.5M + anglenet 378KB ≈ 4.7 MB + 引擎 | 未核 | 未核 | 中 | **GPL-2.0 与本项目自定义协议冲突**（传染性开源义务），不可选 |
| `RapidOCR`（onnxruntime-android） | **Apache-2.0**（代码与转换模型同） | 第三方横评中印刷中文/街景/手写均进前三 | 不需要 | ✅ 离线 | 模型来自 PaddleOCR（同量级）+ onnxruntime-android `.so` | 未核 | 未核 | 中-高 | 需自带 onnxruntime AAR + 前/后处理；Android 端集成资料弱于 Paddle |

> 体积口径说明：均为**单 ABI（arm64-v8a）+ DEFLATE 压缩**的增量估算，非构建后 APK 实测。基线 APK 实测 28,820,441 字节（27.49 MiB）。真实增量受 ABI splits、`useLegacyPackaging`、R8、资源裁剪影响，**必须构建后实测**（§6）。

### 5.2 结论与选择

**默认推荐：Tesseract4Android 4.8.0 + `tessdata_fast/chi_sim`。**

三条理由，按权重排：

1. **它是唯一同时满足 LICENSE 全部三条承诺的候选。** 代码无网络组件、AAR 不声明任何权限、无 Play services 依赖、无 telemetry client（§3.2 实测）。ML Kit 做不到（§2.4）；`chineseocr_lite` 因 GPL 出局。
2. **离线语义干净。** 模型随 APK 走，首次只有一次本地解压，不存在「首次请求返回空结果」的窗口。ML Kit unbundled 的官方失败语义是「推理请求会失败」，对排序功能是静默降级风险。
3. **增量可接受。** 单 ABI ≈ 4.65 MiB，相对 27.49 MiB 基线约 +17%；若改多 ABI 或换 `tessdata_best`（13,077,423 字节）需重新评估。

**备选：ML Kit 中文 bundled**，仅在下列条件**同时**成立时启用：

- 项目愿意把 LICENSE 第 6 条从「不收集任何用户行为数据」改写为「不上传照片或图片内容；使用 Google ML Kit 时会上报设备/应用/性能指标」，并在「关于」页明示；
- 接受中文 OCR 精度与初始化速度换维护成本（bundled 停在 2024-08，无 bugfix 预期）；
- 接受体积比 Tesseract 略大（≈5.8 vs ≈4.65 MiB/ABI）。

**何时换方案**（触发条件，可执行判定）：

| 触发条件 | 动作 |
| --- | --- |
| 实测分类准确率（六类意图）低于 70% | 先调 `psm` / 预缩放 / 二值化；仍不行则评估 PaddleOCR mobile |
| 单图识别 P95 超过 800 ms（中端机） | 换 Tesseract4Android **openmp** 变体（同坐标 `-openmp`，arm64 `.so` 8.32 MiB vs 7.51 MiB），或降采样 |
| 需要竖排中文（`chi_sim_vert`，1,927,902 字节） | 追加模型；体积 +1.93 MB |
| 精度不够且体积可放宽 10 MB | 换 `tessdata_best/chi_sim`（13,077,423 字节），精度档位从「最低」升到「最高」 |
| 项目决定放弃零遥测 | 直接换 ML Kit bundled，精度与初始化体验更好 |

### 5.3 与「不上传任何照片或个人信息」承诺的冲突分析

**这是本文档最需要写清楚的一节：必须把「图片不外传」和「零遥测」拆成两件事判。**

| 承诺条款（LICENSE §6） | ML Kit bundled | Tesseract4Android | 判定口径 |
| --- | --- | --- | --- |
| 🔒 100% 本地处理，所有数据存储在用户设备本地 | ⚠️ **推理在本地**，但指标离设备 | ✅ 成立 | 推理位置与数据留存要分开看 |
| 🙈 不收集任何用户行为数据 | ❌ **不成立**（见下） | ✅ 成立 | 决定性条款 |
| 🛡️ 不上传任何照片或个人信息 | ⚠️ **图片内容不传，但周边元数据传** | ✅ 成立 | 需细分「照片」的定义 |

对 ML Kit 的精确陈述（三条，都可引官方原文）：

1. **图片本身确实不外传。** [ML Kit Terms](https://developers.google.com/ml-kit/terms) 原文：「processing of the input data (e.g. images, video, text) **fully happens on-device**, and ML Kit does not send that data and the resultant outputs to Google servers」。这一条对「不上传照片」是**成立的**。
2. **但会周期性联网取资源。** 同页：「The ML Kit APIs **may contact Google servers from time to time** in order to receive things like bug fixes, updated models and hardware accelerator compatibility information.」——注意「updated models」：bundled 也可能拉模型更新。
3. **并且会主动上报指标。** 同页：「The ML Kit APIs also **send metrics about the performance and utilization** of the APIs in your app to Google.」字段清单见 §2.4，含**设备型号/OS build、包名与版本、每次安装标识符、延迟、图片格式与分辨率、事件类型、错误码**。

因此准确表述是：

> **ML Kit 不外传图片，但它是一个遥测在场（telemetry-on）的组件。它满足「不上传任何照片」，但不满足「不收集任何用户行为数据」。**

对「个人信息」的判断：官方把该标识符描述为「不用于唯一标识用户或实体设备」，设备型号 + OS build + 包名 + 每次安装标识符的组合，按常见隐私口径属于**设备/应用层技术数据**，不构成账号级个人身份信息，但**属于「用户行为数据」范畴**（事件类型、使用频度、错误码即行为）。所以 LICENSE 第 6 条第 2 项是硬冲突，第 3 项是**边界冲突**（取决于「个人信息」如何定义，不宜由工程单方判定）。

另一条要补充的：ML Kit ToS 把告知义务压给开发者——「**You are responsible for informing users of your app about Google's processing of ML Kit metrics data as required by applicable law.**」即采用 ML Kit 就**必须**在隐私说明里增加一段，这与 LICENSE 第 6 条末尾「任何修改版本也应遵守上述隐私承诺」一起，构成对协议文本本身的修改需求。

**关于仓库里那个可选的 `DeepSeekTextAssist`**：它默认关闭（`textAssist: TextAssist? = null`），只发脱敏文本、不发图片。但它一旦被用户开启，就使「100% 本地处理」在**文字层面**不再成立。这与本节的 ML Kit 判定不同源（一个是默认关闭的可选功能，一个是依赖自带的常驻遥测），但两者都会让 LICENSE 第 6 条的措辞需要重写。这一点应在 #94 的协议更新任务里合并处理，不要分开决策。

---

## 6. 本次未验证的部分

严格分区：**A = 实测/官方原文可复查**，**B = 推断（未验证）**，**C = 必须设备实测**。

### A. 已实测或官方原文支撑（可直接引用）

| 结论 | 证据 |
| --- | --- |
| 三个构件的字节数 2,036,186 / 78,105 / 2,469,156 | 本机 `curl` 下载 + `Content-Length` 双重核对 |
| ML Kit 中文 assets 合计 2,490,310 字节（未压缩），1,705,557（压缩） | 解包 `text-recognition-chinese-16.0.1.aar` 逐文件统计 |
| `Hani_ctc/…/lstm_model.fb` = 891,872 | 同上 |
| `text-recognition-bundled-common:17.0.0` 传递依赖 17,960,748 字节，arm64 `.so` 11,064,544 | 解包 POM 依赖树后实测 |
| ML Kit 许可证 = ML Kit ToS | bundled AAR 的 POM `<licenses>` 块 |
| AAR 清单 minSdk = 21（两个 ML Kit AAR + Tesseract AAR） | 解包 `AndroidManifest.xml` |
| 官方正文要求 API 23 vs 页首摘要 21 | 官方 Android 指南页两种表述并存 |
| Tesseract4Android 每 ABI `.so` 明细 | 解包 `tesseract4android-4.8.0.aar` |
| 根聚合坐标会拉入 standard + openmp 两个 AAR | 根 POM 依赖块 |
| 老坐标 4.9.0 = 404（JitPack 构建 `Error`） | JitPack API + 直连构件 |
| `chi_sim.traineddata` 与仓库资产逐字节一致 | SHA-256 双向比对 |
| 各 tessdata 变体体积（fast 2.47 MB / best 13.08 MB / legacy 44.37 MB / vert 1.93 MB） | `Content-Length` |
| 现有 APK 基线 28,820,441 字节 | `app/release/TabulaV3.3.3.apk` |
| 本项目已声明 `INTERNET` 权限 | `app/src/main/AndroidManifest.xml:18` |
| 无官方 ML Kit 遥测 opt-out | issue #593（2022 至今 Open）+ #1076（Open，无官方回复） |

### B. 推断，未经验证（**不得当结论引用**）

1. **「bundled 可在无 GMS 设备上运行」——未验证。** 我在 AAR 层面确认了模型与 `.so` 都在包内，但 ML Kit 的 `common`/`play-services-base`/`play-services-basement` 仍是传递依赖，初始化路径是否要求 GMS 存在，**没有找到官方明确承诺**。社区长期有相关提问（[issue #605](https://github.com/googlesamples/mlkit/issues/605)、[issue #241](https://github.com/googlesamples/mlkit/issues/241)），结论不一致。国产 ROM 无 GMS 的场景必须单测。
2. **§5.1 表里的「APK + 模型增量」是单 ABI 压缩态估算**，不是构建产物实测。四 ABI 全带时的真实增量、R8/资源裁剪的实际影响、`android:extractNativeLibs` 策略的影响，均未测。
3. **Tesseract 对中文 App 截图的准确率——完全没有数据。** §3.6 引用的测评数据集不含 UI 截图；本项目六分类的准确率无任何公开可比对象。「Tesseract 会因小字/紧凑行距出错」是基于 issue #1624 行切分问题的**外推**，不是对截图场景的实测。
4. **PaddleOCR / RapidOCR 的 Android 端体积与性能**未实测（只引了官方模型表与仓库元数据）；`chineseocr_lite` 的 minSdk 与引擎 `.so` 体积未核。
5. **ML Kit 与 Tesseract 的中文精度相对关系**未做同图对比。现有证据只能支撑「第三方横评里 Tesseract 未进印刷中文前三」，不能支撑「Tesseract 比 ML Kit 差多少」。

### C. 必须设备实测（建议作为 #94 验收项）

1. **构建产物体积**：加依赖后打 release APK（R8 + 资源裁剪 + 当前 signing 配置），对比 28,820,441 字节基线；分别测 `abiFilters` 单 arm64 与全 ABI 两种。
2. **首次初始化开销**：assets 解压 2,469,156 字节到私有目录的耗时 + `TessBaseAPI.init()` 耗时（中端机，冷/热各 5 次）。
3. **单图识别耗时**：对 1080×2400 级截图，测 P50/P95；对比 Standard 与 OpenMP 变体。
4. **六类意图的分类准确率**：用仓库 `app/src/test/resources/intent_samples/` 的样本 + 真实截图，按「优惠券 / 新闻 / 分享页 / 短信验证码 / 活动提醒 / 订单票据」逐类统计，目标 ≥70%（低于则进 §5.2 换方案流程）。
5. **`psm` 与预处理参数**：小字截图下 `psm 6/7/11` 的差异、是否需要 2× 上采样、深浅色主题（白底黑字 vs 黑底白字）是否需反色。
6. **低存储/降级路径**：私有目录写满、解压失败时 `OcrResult.Unavailable` 是否如期降级为「证据不足」而不阻塞清理。
7. **无 GMS 设备验证**（若最终选 ML Kit 备选）：国产 ROM / 无 Play 商店设备上 bundled 是否能 init 成功。
8. **隐私可验证性**：用 `tcpdump`/Network Profiler 对含 Tesseract 的 release 包做一次全流程抓包，确认应用 UID 无出站请求（这是兑现 LICENSE 第 6 条的**唯一实证方式**，建议固化为回归项）。

---

## 7. 参考来源清单

官方文档（Google）

- [ML Kit Terms & Privacy](https://developers.google.com/ml-kit/terms) — 闭源限制、本地处理、指标上报、开发者告知义务
- [Android 数据披露](https://developers.google.com/ml-kit/android-data-disclosure) — 采集字段全表
- [ML Kit 模型安装路径](https://developers.google.com/ml-kit/tips/installation-paths) — bundled/unbundled/动态下载差异
- [使用机器学习套件识别图片中的文本（Android）](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) — 体积对比、minSdk 23 表述、库坐标
- [文字识别 v2（概览）](https://developers.google.com/ml-kit/vision/text-recognition/v2) — API 21+ 表述
- [文字识别 v2：支持的语言](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages) — `zh` 为 Supported
- [ML Kit 版本说明](https://developers.google.com/ml-kit/release-notes) — 16.0.1（2024-08-07）/ 16.0.2（2026-10-26）
- [已知问题](https://developers.google.com/ml-kit/known-issues) — Text Recognition + CameraX/Camera2 性能提示
- [设置 Google Play 服务](https://developers.google.com/android/guides/setup) — 无 Play 商店设备不装 GMS

官方仓库与构件

- [adaptech-cz/Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) — Apache-2.0、minSdk 21、变体说明、解压到私有目录的要求
- `https://jitpack.io/com/github/adaptech-cz/Tesseract4Android/tesseract4android/4.8.0/tesseract4android-4.8.0.aar`（12,832,786 字节）
- `https://jitpack.io/com/github/adaptech-cz/Tesseract4Android/Tesseract4Android/4.8.0/Tesseract4Android-4.8.0.pom`（聚合 POM，拉两个变体）
- `https://jitpack.io/api/builds/com.github.adaptech-cz/Tesseract4Android`（4.9.0 = `Error`）
- `https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/16.0.1/text-recognition-chinese-16.0.1.aar`（2,036,186 字节）
- `https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-mlkit-text-recognition-chinese/16.0.1/play-services-mlkit-text-recognition-chinese-16.0.1.aar`（78,105 字节）
- `https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-bundled-common/17.0.0/text-recognition-bundled-common-17.0.0.aar`（17,960,748 字节）
- `https://github.com/tesseract-ocr/tessdata_fast/raw/main/chi_sim.traineddata`（2,469,156 字节）
- `https://github.com/tesseract-ocr/tessdata_fast/blob/main/LICENSE`（Apache-2.0）

Issue 与第三方测评

- [googlesamples/mlkit #593](https://github.com/googlesamples/mlkit/issues/593) — 请求关闭 metrics 上报，2022 至今 Open
- [googlesamples/mlkit #1076](https://github.com/googlesamples/mlkit/issues/1076) — 无网络权限 + bundled 的实测观察，Open，无官方回复
- [googlesamples/mlkit #605](https://github.com/googlesamples/mlkit/issues/605)、[#241](https://github.com/googlesamples/mlkit/issues/241) — GMS 依赖与离线模块的历史提问
- [tesseract-ocr/tesseract #1624](https://github.com/tesseract-ocr/tesseract/issues/1624) — 中文误识 + 行切分/字号敏感
- [tesseract-ocr/tessdata #148](https://github.com/tesseract-ocr/tessdata/issues/148) — `tessdata_fast` = 最快/最不准 的官方档位说明
- [中国电子信息测试服务平台：十二款开源 OCR 开箱测评](https://www.ceietn.com/a/zhengcefagui/guojiaji/930.html) — 印刷中文前三不含 Tesseract；变形字体普遍低
- [PaddleOCR 文本识别模型文档](https://www.paddleocr.ai/latest/en/version3.x/module_usage/text_recognition.html) — 各模型体积与准确率
- [PaddlePaddle/PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)、[PaddlePaddle/Paddle-Lite](https://github.com/PaddlePaddle/Paddle-Lite) — Apache-2.0
- [DayBreak-u/chineseocr_lite](https://github.com/DayBreak-u/chineseocr_lite) — GPL-2.0，模型合计 4.7 MB
- [RapidAI/RapidOCR](https://github.com/RapidAI/RapidOCR) — Apache-2.0（代码与转换模型）、[RapidOcrAndroidOnnx](https://github.com/RapidAI/RapidOcrAndroidOnnx)

---

## 8. 复核方法（让后人能重跑）

```bash
# 1) 三个构件体积
curl -sSL -o a.aar https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/16.0.1/text-recognition-chinese-16.0.1.aar
curl -sSL -o b.aar https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-mlkit-text-recognition-chinese/16.0.1/play-services-mlkit-text-recognition-chinese-16.0.1.aar
curl -sSL -o chi_sim.traineddata https://github.com/tesseract-ocr/tessdata_fast/raw/main/chi_sim.traineddata
ls -l a.aar b.aar chi_sim.traineddata   # 期望 2036186 / 78105 / 2469156

# 2) 解包统计 assets 与 so（Python 内置 zipfile，无需 unzip）
python -c "import zipfile;z=zipfile.ZipFile('a.aar');a=[i for i in z.infolist() if i.filename.startswith('assets/')];print(len(a),sum(i.file_size for i in a))"
# 期望 25 2490310

# 3) 校验仓库内模型与官方一致
sha256sum chi_sim.traineddata   # 期望 a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730

# 4) 许可证来自构件本身，而非文档
curl -sSL https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/16.0.1/text-recognition-chinese-16.0.1.pom | grep -A3 licenses
```
