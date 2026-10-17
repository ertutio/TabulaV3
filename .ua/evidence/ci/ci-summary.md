# TabulaV3 CI 与性能基线证据

采集日期：2026-10-17。此目录保存 GitHub 只读 API 原始响应，以及从既有下载产物 XML/日志核出的结果；本次没有运行 Gradle、设备测试或触发 CI。

## 固定源码与 checkout

- 远端 master：`d2038b8e0dc8c99fbdf2c754e88dec0a8078a725`。
- 分析使用固定源码归档；来源与 codeload SHA-256 见本结果目录根部 source-manifest.json；这是源码归档，没有 .git 元数据。
- `work/TabulaV3-volume-fix` 与 `work/TabulaV3-performance` 没有独立 Git 元数据；前者是构建与设备证据目录，后者是 PR6 计划/协议/脚本快照。
- `work/TabulaV3-pr5` 是唯一实际 checkout：origin 为 `https://github.com/ertutio/TabulaV3.git`，分支 `fix/recycle-bin-error-consistency`，HEAD `e24d822e237edbe020598f8fd13623eb2606a751`，本地有修改和未跟踪文件，且没有 origin/master 或 origin/HEAD 引用。它不是干净的 master checkout。其 worktree 清单仅列出自身；顶层空仓库也无有效提交。

## 适用文档和测试入口

- 当前快照没有 `AGENTS.md`、架构文档或 `docs/acceptance.md`。项目 Markdown 只有 README 和 `docs/release-3.3.4.md`。
- README 与 `app/build.gradle.kts` 当前版本为 3.3.5 / versionCode 5；release note 仍是 3.3.4 / versionCode 4，故它是旧版发布说明，不是当前验收定义。
- Release 单元入口：`:app:testReleaseUnitTest`。Debug 单元入口：`:app:testDebugUnitTest`。Android instrumentation 源码在 `app/src/androidTest`，当前有 1 个测试类；CI 构建入口包括 `:app:assembleDebugAndroidTest`，设备 workflow 再运行 MediaStore 探针。
- 已下载的 PR7/volume-confirmation release XML 有 14 个 suite 文件，合计 75 tests、0 failures、0 errors、0 skipped。API 36 与 API 37 的既有 instrumentation 日志分别记录 `OK (3 tests)`；设备 JSON 均标注合成 JPEG、真实 MediaStore 查询与 journal reconciliation。

## PR6 性能基线状态

- PR6 当前仍为 open + draft，head `eb8b94b821635838b00dfbb957d6969ca48a4583`，base `474129ed4d5262befc8bdab0fbd60e4df81411f2`，落后于当前 master；没有已冻结的报告或数字。
- 最新 PR6 run `38573946674` 于 2026-10-14 完成：只有 `paused` gate 成功；`build`、`measure`、`report` 均 skipped；无 artifacts。PR6 workflow 的 build job 当前显式 `if: ${{ false }}`。
- 前一轮 `38572253086` 的 build job 在构建 release-derived app 与独立 measurement APK 步骤失败，measure/report skipped，无 artifacts；`38573180519` build 取消。现存计划和协议不构成测量结果，性能基线仍暂停且无有效冻结证据。

## 可复用的最近成功 CI

- 远端 master 与成功 run `38676233496` 的 commit SHA 不同，但两者 Git tree SHA 完全相同：`120001e8b1e4809b7e942c466686a3813bbaf3c3`（递归树 264 entries，未截断）。因此 tracked source、tests、workflow 和工具输入完全一致，run7 证据适用于当前 master；比较依据是 tree hash，不是 SHA 相同。
- run `38676233496` (2026-10-15, push, `fix/recycle-bin-volume-confirmation`) 的 build、API36 device job、API37 device job 全部 success。Build 覆盖 release 单测、release APK、debug APK、instrumentation APK 和包/混淆检查；两个模拟器运行真实 MediaStore/pending-record 探针。
- 远端 artifacts 的 digest 与本地既有 ZIP 实际 SHA-256 完全相同：build `12af2b47f821cac082779471acea206766bfd10fc0ad35dbe1f4a497f4b2d823`；API36 `b5ffb494ddfecc0adbd2ab3ce8dcd73905e6fdc69b5991f00bacdc3f0a92937b`；API37 `52ad252ecb9b15c669930b4d5dd5693b9f9547f016c0891209ee2f4b64dff227`。
- 同分支较早 run `38675609117` 的 unit/build 和 API36 成功，但 API37 device job 失败；run `38676180550` 取消；run7 随后两端均成功。

## 适用边界

所有已复用的设备证据来自 Ubuntu GitHub Actions 上的 Android x86_64 Google APIs 模拟器（API 36 / 37），使用合成照片；这能证明对应代码树上的单元和模拟器 MediaStore 路径通过，不能替代 ColorOS 真机覆盖，也没有提供性能基线数据。当前 master 查询没有直接关联的 Actions run/check/status；workflow 都是特定分支 push 或手动触发，没有 pull_request 或 master push 触发器。

