# Tabula 模拟器性能基线 v1

应用源码提交：474129ed4d5262befc8bdab0fbd60e4df81411f2

两轮独立云模拟器数据。帧耗时为每轮分位数的汇总；内存是动作后快照。
数值越小通常越好，但不代表实际手机表现。两轮差异用于提示云端噪声，不是改善结论。

| 指标 | 样本数 | 中位数 | P95 | A / B 中位数 | 两轮差异 |
|---|---:|---:|---:|---|---:|
| 进程冷启动 TTID（ms） | 20 | 1762.54 | 2157.43 | 1647.86 / 1830.21 | 10.5% |
| 热启动 TTID（ms） | 20 | 1044.78 | 1491.37 | 842.86 / 1295.00 | 42.3% |
| 切牌：每轮 CPU 帧耗时 P95（ms） | 10 | 148.20 | 158.02 | 139.09 / 154.82 | 10.7% |
| 摸牌：每轮 CPU 帧耗时 P95（ms） | 10 | 146.90 | 160.35 | 138.07 / 156.77 | 12.7% |
| 大图：每轮 CPU 帧耗时 P95（ms） | 10 | 237.29 | 246.39 | 236.96 / 237.62 | 0.3% |
| 内存 startup Total PSS（MB） | 10 | 66.72 | 67.47 | 67.02 / 66.47 | 0.8% |
| 内存 after12Swipes Total PSS（MB） | 10 | 80.78 | 85.46 | 80.92 / 80.48 | 0.5% |
| 内存 viewer Total PSS（MB） | 10 | 86.04 | 86.46 | 86.39 / 85.74 | 0.8% |
| 内存 afterViewerClose Total PSS（MB） | 10 | 85.65 | 86.13 | 86.05 / 85.43 | 0.7% |

完整样本及环境见 baseline.json，表格见 metrics.csv；原始 JSON 和 Perfetto trace 保存在对应 Actions 产物。
后续推荐用同一工作流同时测固定基线和候选提交，两个会话反向执行版本顺序。
模拟器镜像、测量协议或渲染配置变化时，应建立新基线，而非直接沿用冻结数字。
普通整理进度、分享接收端及真机兼容性不属于本次性能测量。

## 采集与验收

- 工作流： [performance-baseline.yml run 43387329019](https://github.com/ertutio/TabulaV3/actions/runs/43387329019)。build、measure A、measure B、report 四个 job 均成功，最终报告 valid=true。
- 固定 A/A 输入：测量应用源码提交 474129ed4d5262befc8bdab0fbd60e4df81411f2；candidate_commit 为空；emulator_build 为 16740729。此数据是旧固定基线的重复测量，不是与当前 master 的性能比较。
- Harness 提交：55b8edf6154a550cf2abd8fd5450e7b972a17455。应用 APK SHA-256 为 7e7e23aa3bf972c31c89a569982d52af50482081ed8dccf7fc1a02cfdb8c0c4f（28,461,433 bytes）；protocol SHA-256 为 ae16da1e1713493d39be1e3160eeb3cf290a2c9a153032af4ab3f601e4502ef8。
- 两会话均为 Android API 36、Android emulator 37.2.13.0 build 16740729，使用同一 protocol 与数据集。

| Session | Left swipes | Right swipes | Total swipes | viewerOpenClose | decodedFixtureSmoke | fixture-smoke.png retained |
|---|---:|---:|---:|---:|---:|---|
| A | 120 | 60 | 180 | 31 | 1 (16 colorful samples) | yes, 175,761 bytes; SHA-256 32ede9d68dfed6bcca926e8cb4c90085c1a09b6708d0b3aa0f59fd96a7f76353 |
| B | 120 | 60 | 180 | 31 | 1 (15 colorful samples) | yes, 171,661 bytes; SHA-256 e468bcee14c5e59772dc3e283b15f8ac288ea917908422398957e330b6550bde |

Each session recorded 212 operations and instrumentation output OK (7 tests), including a0_fixtureSmoke.

| Artifact | ID | Size | SHA-256 |
|---|---:|---:|---|
| TabulaPerf-v1-reference | 14019379482 | 26,855 bytes | 01ebf7f030ce400cc134b9e4f86364649c8d13a80a60c0dab50904d6cb40c009 |
| TabulaPerf-v1-session-a | 14019259555 | 96,527,155 bytes | e66063c8354fea776eb3cfb683ee627c07ab36aebe4f120d54a5e35b479deedd |
| TabulaPerf-v1-session-b | 14019294444 | 98,120,408 bytes | 29fc719559091bc9ecf17ed221b12bdfbda26934d841d38f34d455a5f0cbbb03 |

热启动 A/B round median 为 842.86 / 1295.00 ms，差异 42.3%。该差异记录为本次 A/A 的重复测量波动，具体来源尚未归因，后续比较需结合此波动判断。此轮没有运行设备变异红控制。原始 ZIP、日志及 Perfetto trace 保留在对应 GitHub Actions artifacts 中，本目录不包含这些大型文件。
