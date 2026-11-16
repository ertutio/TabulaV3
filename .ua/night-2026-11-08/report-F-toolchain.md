# F1：工具链与交付兼容盘点（主代理执行，2026-11-08 17:07–17:09）

基线：master `43a8da1`。方法：读仓库实际配置 + 官方文档核对（来源附后）。

## 1. 版本表（声明 vs 实际引用）

| 项 | 版本 | 实际使用 | 备注 |
| --- | --- | --- | --- |
| Gradle | 8.11.1 (wrapper) | 是 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 8.9.0 | 是 | `gradle/libs.versions.toml` |
| Kotlin | 2.3.0 | 是 | 同上；compose 插件同版本（`org.jetbrains.kotlin.plugin.compose`） |
| Compose BOM | 2024.12.01 | 是 | ui/material3/animation/foundation/icons-extended 全引用 |
| jvmTarget/JDK | 17（Temurin） | 是 | 本机 Temurin 17.0.20.1 = CI setup-java temurin 17 |
| compileSdk/targetSdk | 36 / 36 | — | minSdk 31 |
| Coil | 2.7.0（compose+gif） | 是 | 渲染主链 |
| coroutines | 1.9.0（android+test 同版本） | 是 | PR40 确定性调度测试用同版本 |
| exifinterface | 1.3.7 | 是 | EXIF/实况路径 |
| androidx.media | 1.7.0 | 是 | Fluid Cloud 注释（build.gradle 引用） |
| konfetti | 2.0.4 | 是 | BatchCompletionScreen.kt |
| **media3** | 1.5.1 | **否** | **仅 catalog 声明，build.gradle.kts 无引用、main 无 import——未使用**（与暂缓方向登记一致） |
| Robolectric | 4.12.2 + @Config(sdk=[34]) | 测试 | 4.12 支持 SDK 34/35 ✓ |
| test runner | androidx.test:runner 1.6.2 / ext.junit 1.2.1 | androidTest | 与 benchmark 工具 metadata 一致 |
| lifecycle | 2.8.7（runtime/viewmodel/process） | 是 | — |
| core-ktx / activity-compose | 1.15.0 / 1.9.3 | 是 | — |

## 2. 官方兼容核对（引用）

| 组合 | 官方要求 | 本仓库 | 结论 |
| --- | --- | --- | --- |
| AGP 8.9 ↔ Gradle | 最低 8.11.1（[官方 AGP 兼容表](https://developer.android.com/build/releases/gradle-plugin)） | 8.11.1 | ✓ 精确匹配 |
| AGP 8.x ↔ JDK | 最低 17 | 17（本地+CI 一致） | ✓ |
| compileSdk/API 36.0 | **最低 AGP 8.9.1**（官方表 "API 36.0 → Min AGP 8.9.1"） | **AGP 8.9.0** | ⚠️ **低于官方最低 8.9.1**（见问题①） |
| Kotlin 2.3.0 ↔ Compose compiler | Kotlin 2.0 起 compose 插件随 Kotlin 同版发布，无需 kotlinCompilerExtensionVersion（[Kotlin 2.3.0](https://kotlinlang.org/docs/whatsnew23.html)、[Compose↔Kotlin 映射](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)） | 插件=kotlin 同版 2.3.0 | ✓ |
| Kotlin 2.3 ↔ R8 | 官方在 AGP 8.13.2 才"更新 kotlin-metadata-jvm 至 2.2.10 支持 Kotlin 2.3"（[AGP 8.13.2 release notes](https://developer.android.com/build/releases/gradle-plugin)） | AGP 8.9.0 + Kotlin 2.3.0 | ⚠️ 见问题②（实际构建已证明可用） |
| Play targetSdk 政策 | 2026-08-31 起新应用/更新须 targetSdk≥36（[Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)、[developer.android.com](https://developer.android.com/google/play/requirements/target-sdk)） | targetSdk 36 | ✓ 已达标 |
| Coil 2.7.0 | Kotlin 2.0 构建，元数据向后可读 | 消费端 Kotlin 2.3.0 | ✓（Coil 2.x 官方文档兼容口径） |

## 3. 问题清单

**① AGP 8.9.0 < API36 官方最低 8.9.1（P2，最小关联修复组=单版本号）**
官方表要求 API 36.0 用 AGP ≥8.9.1；本仓库 8.9.0。实际影响：CI Release/R8 构建全绿（run 42461895694），属"可用但低于官方下限"。修复：AGP 8.9.0→8.9.1（patch 升级，Gradle 8.11.1/JDK 17 不变，零 API 变化）。建议下一独立小 PR 验证（assembleRelease + testReleaseUnitTest + 设备门禁一次）后合并；本轮未实施（避免冻结前引入构建变量）。

**② R8/Kotlin 2.3 元数据（P3 登记，不修复）**
AGP 8.13.2 才官方声明 kotlin-metadata 支持 Kotlin 2.3；本仓库 AGP 8.9.0 + Kotlin 2.3.0 组合的 Release/R8/覆盖检查 CI 全绿为**实证可用**。若未来 R8 报元数据错误，回退=升级 AGP（与①同向）。登记依据链完整，不作为当前缺陷。

**③ media3 未使用（P4 清理候选，不本轮）**
catalog 声明 1.5.1 但 build.gradle 无引用。删除属清理项，收益仅构建配置整洁；按暂缓方向维持原范围，不动。

**无其他兼容问题**：Compose BOM/coil/coroutines/lifecycle 组合与 Kotlin 2.3 无已知冲突；Robolectric sdk=[34] 受支持。

## 4. Release/覆盖安装证据现状

- CI `build-volume-confirmation.yml` 已验证：Release 构建非 debuggable、R8（mapping 产物）、签名（TABULA_KEYSTORE_FILE 等 env 注入 forkRelease；CI secrets 持有）、包名 com.tabula.v3、体积检查（acceptance.md 记载的既有门禁）。
- versionCode 5 / versionName 3.3.5（RELEASE_DATE 2026.10.13）；覆盖安装条件=versionCode 递增+同 fork 签名（既有口径）；debug 测试包身份 `com.tabula.v3.recyclebin.test`（applicationIdSuffix），不证明正式包覆盖安装——与验收约定一致。
- **缺口**：覆盖安装与旧数据恢复的人工核对清单（docs/upgrade-verification-checklist.md，U11 登记）仍未实际执行——维持 `待验证`，需设备/授权。
- 本机无法签名（secret 在 CI）；TC43/TC44 的 Release 证据=CI 门禁为唯一权威，已绿。

## 5. T12 依赖逐个处理排序（按实际使用必要性）

| 序 | 依赖 | 现版本→最新稳定 | 升级必要性 | 风险 | 建议动作 |
| --- | --- | --- | --- | --- | --- |
| 1 | Coil（compose+gif） | 2.7.0 → 2.8.x | 渲染主链，2.8 有修复；但 2.x→2.8 需验证键/HD 层行为（PR34/39 键链） | 中（图像管线） | 定点回归（DeckViewerThumbnailKeyTest/CoverImageRequestTest/OwnedBitmapsTest）后再升；本轮不动 |
| 2 | exifinterface | 1.3.7 → 1.4.x | EXIF/实况数据路径 | 低 | 单独 PR+定点回归（EXIF 旋转/实况识别用例需先确认存在） |
| 3 | coroutines | 1.9.0 → 1.10.x | 已有取消/并发回归覆盖（PR40/50），验证成本低 | 低 | 同上 |
| 4 | androidx.media | 1.7.0 | Fluid Cloud 单点使用 | 低 | 维持 |
| 5 | konfetti | 2.0.4 | 纯庆祝动画 | 无 | 维持 |
| — | Compose BOM | 2024.12.01 | 大版本迁移=暂缓方向（路线图明确） | — | 不动 |

## 6. 结论

T09/T12 前置盘点完成：**一项真实修复候选（AGP→8.9.1，独立小 PR）+ 三项登记（R8 元数据、media3 未使用、覆盖安装人工清单待执行）**；无阻断性兼容问题；CI Release 门禁为交付身份的现行权威证据。
