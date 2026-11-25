# 卡点交接：液态玻璃四 PR（#89/#90/#91/#92）—— 2026-11-24 夜

生成时间：2026-11-24 23:55（Asia/Shanghai）
工作目录：`D:\VIBEcoding`（Windows，MSYS bash）
分支/HEAD：见下表；**`master` 仍是 `faf6e25`，未合并、未直推、未发版**

这份文档是给**接手排查的人**看的：只写现状、证据、已经排除的可能，
不写猜测性结论。凡是没跑过的，一律标「未执行」。

---

## 0. 一句话

**#89 已真正跑通**（`OK (5 tests)`）。**#91/#92 的探针 23 项检查全部通过**，
卡在两处，且是两件不同的事：

1. **#92**：设备测试 `RecycleBinViewerBackdropDeviceTest` 首次真跑，
   卡在 `waitFor("回收站")` 超时——**这是实现/夹具问题，需要人看**；
2. **#91/#92**：模拟器在探针跑到后半段时整体掉线（`uiautomator dump`
   报 `device not found`），**这是 CI 环境问题**。

三态语义（通过 / 断言失败 / 未完成）已经能正确区分这两类，没有互相掩盖。

---

## 1. 分支与 HEAD

| 路径 | 分支 | HEAD | PR | 状态 |
|---|---|---|---|---|
| `TabulaV3` | `audit/combined-partial-2026-11-24` | `a5574d5` | — | 组合审计分支（非 PR） |
| `TabulaV3-balance` | `feature/source-balance-2026-11-23` | `2bfdd9f` | **#89** | **设备测试通过** |
| `TabulaV3-round` | `feature/round-intent-2026-11-23` | `b641025` | **#90** | 堆叠在 #89 上，未重跑 |
| `TabulaV3-glass` | `feature/liquid-glass-material-2026-11-23` | `c8c21f5` | **#91** | 探针 23 项 OK，模拟器掉线 |
| `TabulaV3-glass-topbar` | `feature/liquid-glass-topbar-actions-2026-11-23` | `ae585b0` | **#92** | 探针 23 项 OK，设备测试红 |
| `TabulaV3-roadmap` | `docs/liquid-glass-parallel-roadmap-2026-11-23` | `e2c6660` | #93 | 文档 |

依赖链：

```
master (faf6e25)
 ├── #89 2bfdd9f  ← 独立，已绿
 │    └── #90 b641025  ← 堆叠在 #89 上（#89 未合并，base 未改）
 └── #91 c8c21f5  ← 独立
      └── #92 ae585b0  ← rebase 到 #91 最新
```

---

## 2. 本轮修掉的缺陷（都已推送）

### 2.1 #89 —— 两个自身错误，已绿

run [45389685618](https://github.com/ertutio/TabulaV3/actions/runs/45389685618)
（`2bfdd9f`）：`OK (5 tests)` / `instrumentation exit=0` / `result=pass`

| 症状 | 真因 | 修法 |
|---|---|---|
| `切换前页面持有的批次必须全部是本测试造的照片`，多余项全是 `external_primary/.../84` | 夹具写 `MediaStore.EXTERNAL_CONTENT_URI` → `external`；生产按 `VOLUME_NAME` 重建 → `external_primary`。**同一行两种写法**，字符串比必然不等 | 归一成 `(卷, id)` 对账（`MediaUriIdentity`）；`external` 与 `external_primary` 同卷，跨卷同 id 仍不等 |
| `Syntax error: end of file unexpected (expecting "fi")`，把真断言失败盖成 exit 2 | `script:` 被 dash **逐行** `sh -c` 执行，跨行 `if...fi` 直接炸；`INSTRUMENT_EXIT=$?` 恒为空 | 逻辑搬进 `tools/run-walk-strategy-evidence.sh`，只认精确 `OK (5 tests)` |

### 2.2 #91 —— 假绿/假红四个漏洞，已修

| # | 症状 | 真因 | 修法 |
|---|---|---|---|
| 1 | 17 项全 OK 后掉线，汇总判 `probe(no-summary)` | 异常分支只写 `error-summary.json`，`api<N>-summary.json` 没生成 | 任何出口都写 `api<N>-summary.json` |
| 2 | 环境中断报成「断言失败」 | 异常时 `raise` → 退出码 1（= 实现问题） | 改为 2；只有 `failures` 非空才是 1 |
| 3 | `TimeoutExpired: adb wait-for-device` 打断整轮 | 救场调用自己无上限挂起 | `wait_for_device()` 有上限、不抛 |
| 4 | androidTest APK 缺失被判「环境中断」，三次重试后仍 exit 2 | #91 本来就没有那个设备测试（属 #92），APK 不产出 | 判 `na`（按设计不跑），不冒充通过也不假装未完成 |

另：`verify` job 没装 Pillow，新增探针单测 6 项全 `ModuleNotFoundError`。已装依赖，
并让测试在缺 PIL 时 **skip 而非 ERROR**。

### 2.3 #92 —— 设备测试首次真跑，连抓两个真缺陷

| # | 缺陷 | 证据 |
|---|---|---|
| 1 | `@After fun cleanup() = runBlocking { }` 返回非 Unit，JUnit 拒绝加载**整个类** | `Invalid test class ... Method cleanup-d1pmJ48() should be void` |
| 2 | `@Before` 漏了 `hasCompletedOnboarding` / `hasAcknowledgedDisclaimer` | 已补，但**没解决**下面的卡点（见 §3） |

缺陷 1 加了源码级闸门 `tools/check_androidtest_signatures.py`（已做反证：
临时改回表达式体 → `exit=1` 并指出文件行号），已接入 `verify` job。

---

## 3. 卡点 A（需要人看）：`waitFor("回收站")` 超时

### 3.1 现象

run [45421997727](https://github.com/ertutio/TabulaV3/actions/runs/45421997727)（`ae585b0`），
API 33 与 API 35 **都红在同一处**：

```
INSTRUMENTATION_STATUS: test=viewerBackdropFallsBackWhileScrollingZoomingAndPanning
java.lang.IllegalStateException: timed out waiting for 回收站
    at com.tabula.v3.PartialSelectionUi.await(PartialSelectionUi.kt:161)
    at com.tabula.v3.PartialSelectionUi.waitFor(PartialSelectionUi.kt:165)
    at com.tabula.v3.RecycleBinViewerBackdropDeviceTest...invokeSuspend(RecycleBinViewerBackdropDeviceTest.kt:172)
Tests run: 1,  Failures: 1
viewer exit=1
```

完整输出见 `instrumentation-viewer.txt`（随 artifact `Tabula-liquid-glass-emulator-API33`）。

### 3.2 已经排除的

- **不是** `should be void`（上一轮的缺陷已修，类能正常加载、用例真的执行了）；
- **不是**「没装 APK」（日志里 `Success` / `Success` 两次安装都成功）；
- **不是** 权限没给（`@Before` 里 `pm grant READ_MEDIA_IMAGES` 已执行）；
- **不是**「引导页没跳过」——这一轮**已经补了** `hasCompletedOnboarding = true`
  与 `hasAcknowledgedDisclaimer = true`，仍然超时。所以不能继续把原因归给引导页。

### 3.3 现场缺口（这是最需要补的）

`PartialSelectionUi.await()` 超时前会调 `dumpTree(tag)` + `saveScreenshot(tag)`，
写到 **设备侧** `getExternalFilesDir(null)/cloud-ui-failures/`。
**取证脚本目前没有把设备侧证据拉回来**，所以 artifact 里只有
`instrumentation-viewer.txt`，看不到超时那一刻屏幕上是什么。

同样，超时前后没有抓 logcat，所以看不到 `MainActivity` 是否启动、是否崩、
是否卡在别的界面。

### 3.4 需要确认的问题（给排查的人）

1. `ui.launch()` 只做 `am start -n .../MainActivity`，**不等待 Activity 就绪**；
   紧接着 `waitFor("回收站", 30000)`。在 API 33/35 的冷启动 + 3 张夹具照片
   入库后，30s 是否够？（`B2MediaAccessRevisionDeviceTest` 同样 30000ms，
   但它是先 `ui.launch()` 再等 `GATE_BUTTON`，且那套件里有 `clearAppData`）
2. 本测试 `@Before` **没有**清应用数据（其它套件如 `ForegroundPermissionUiTest`
   会 `pm clear`）。上一轮遗留状态（例如权限被 revoke、或 PARTIAL 授权态）
   会不会让 MainActivity 停在权限闸门 `授权访问照片`，而不是主界面？
3. `回收站` 这个锚点有两个来源：`TopBar.kt:225` 的 `contentDescription = "回收站"`
   与 `RecycleBinScreen.kt:1441` 的 `text = "回收站"`。`#92` 改了 `TopBar` 的
   `ActionIconButton`（接入 `backdropState`）——是否可能影响该按钮的语义树
   可访问性？（本轮 probe 里顶栏按钮采样全部通过，但那是**预览页**，
   不是真实 `MainActivity`。）
4. 是否需要像 `B2PermissionRefreshDeviceTest` 那样，先 `pm clear` 再从
   权限闸门走一遍完整链路？

### 3.5 建议的下一步（按优先级）

1. **先补证据**：设备测试失败时抓 `logcat -d`（含 crash buffer）+ 拉
   `cloud-ui-failures/` 目录，再重跑一轮。没有现场证据的话，上面四个问题
   只能靠猜。
2. 拿证据后再决定改哪一处（超时值 / 清数据 / 锚点 / TopBar 语义）。

---

## 4. 卡点 B（CI 环境）：模拟器跑到后半段整体掉线

### 4.1 现象

#91 与 #92 的 API 33 都是：**23 项探针检查全部 `[OK]`**，然后

```
[ENV] 取证中断：RuntimeError: adb shell uiautomator dump /sdcard/ui.xml -> 1
adb: device 'emulator-5554' not found
```

`error-summary.json` 里 `checks=23, failed=0`，`environment_unavailable` 记录掉线原因。
API 31 每轮都绿（它只跑 3 项，会话短）。API 35 更早掉（往往装完 APK 就掉）。

### 4.2 已做过的缓解（都没根治）

- 设备测试**提到探针之前**跑（会话越短越不容易掉）；
- 环境中断允许重试一次（`VIEWER_ATTEMPTS=2`），断言失败不重试；
- `adb wait-for-device` 加上限，不再拖死整轮；
- 探针每采集一项就增量落盘，进程被 SIGKILL 也不丢证据；
- 半截 summary（`success=null`）判**未完成**，不会被读成通过。

### 4.3 还没试过的方向

- 把设备测试与预览探针拆成**两个独立 job / 两次 emulator-runner**；
- 换 `emulator-options`（如 `-gpu swiftshader_indirect` → `swiftshader`，
  或加 `-no-snapshot-save`）；
- 降低探针后半段（位移对照 + 帧统计）的会话时长，或按 API 拆分检查项。

---

## 5. 本地验证数字（可信）

| 分支 | testDebugUnitTest | testReleaseUnitTest | 构建 | tools 测试 |
|---|---|---|---|---|
| #89 `2bfdd9f` | classes=74 tests=**490** f=0 | classes=73 tests=**487** f=0 | assembleDebugAndroidTest ✅ | — |
| #91 `c8c21f5` | — | — | — | **27** 项 ✅ |
| #92 `ae585b0` | classes=82 tests=**583** f=0 | classes=81 tests=**580** f=0 | debug + release + androidTest ✅ | **29** 项 ✅ |

#92 liquidglass 单测 = **141**（workflow 阈值即 141）。

本地构建命令：

```bash
JAVA_HOME='C:/tools/jdk-21' ./gradlew.bat --no-daemon \
  -I /d/VIBEcoding/.tabula-baseline/init-local.gradle \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

---

## 6. 绝不能写成「通过」的项

- #92 设备测试六步断言 —— **零次全部通过**，当前红在步骤 0（进不去回收站）
- #91 API 33/35 探针 —— 23 项 OK 但**未跑完**（后半段掉线）
- #92 API 33/35 探针 —— 同上
- 真机截图 / 滚动掉帧对照 —— 本机 `adb devices` 为空
- #90 当前 HEAD `b641025` 的设备测试 —— 合并 #89 后未触发过

---

## 7. 环境坑（会浪费时间，先看）

### 7.1 行尾

仓库混合 CRLF/LF。**编辑任何文件前先查**：

```bash
git show HEAD:<path> | grep -qU $'\r' && echo CRLF || echo LF
```

`.gitattributes` 只钉 `*.sh` 与 `.github/workflows/*.yml` 为 LF，**故意不给
`*.py` 加规则**（`tools/run-liquid-glass-preview-probe.py` 就是 CRLF，
加规则会把 964 行整体重写成 LF，制造纯噪声 diff）。

本轮踩到：`tools/summarize-glass-evidence.py`、`test_summarize_glass_evidence.py`
是 LF，`.gitattributes` 是 LF，`RecycleBinViewerBackdropDeviceTest.kt` 是 CRLF。

### 7.2 推送（凭据提示是坏的）

```bash
GH_TOKEN=$(gh auth token) && git -c credential.helper= push \
  "https://x-access-token:${GH_TOKEN}@github.com/ertutio/TabulaV3.git" \
  HEAD:refs/heads/<branch>
git remote set-url origin https://github.com/ertutio/TabulaV3.git
```

rebase 后用 `--force-with-lease=<branch>:<sha>`，**必须先 `git fetch`**。

### 7.3 emulator-runner 的 `script:` 是逐行 `sh -c`

不是写文件执行。跨行 `if`、`set -o pipefail`、跨行变量全都不成立。
逻辑一律搬进独立 `bash` 脚本。

### 7.4 本机 MSYS 的两个坑（只影响本地跑测试）

- `os.pathsep` 是 `;`，但 bash 只认 `:` —— 给子进程传 `PATH` 要手工转；
- MSYS 的 `timeout.exe` 是原生 Windows 程序，**无法 exec shell 脚本**
  （会判 126）。脚本因此留了 `TIMEOUT_CMD` 覆盖点供单测置空。

---

## 8. 关键文件索引

**#89 新增/改动**
- `app/src/sharedTest/java/com/tabula/v3/testsupport/MediaUriIdentity.kt` — 卷名归一
- `app/src/test/java/com/tabula/v3/testsupport/MediaUriIdentityTest.kt`（5 项）
- `tools/run-walk-strategy-evidence.sh` — 退出码 0/1/2
- `tools/tests/test_walk_strategy_evidence_script.py`（5 项）
- `app/src/androidTest/java/com/tabula/v3/WalkStrategySettingsDeviceTest.kt` — 断言改用归一身份

**#91 改动**
- `tools/run-emulator-evidence.sh` — 设备测试先跑、`wait_for_device`、`na` 判定
- `tools/run-liquid-glass-preview-probe.py` — 三态尾部、增量落盘、adb 重试
- `tools/summarize-glass-evidence.py` — `success=null` 判未完成
- `tools/tests/test_preview_probe_tristate.py`（6 项）
- `tools/tests/test_evidence_order_and_retry.py`（3 项）

**#92 改动**
- `app/src/androidTest/java/com/tabula/v3/RecycleBinViewerBackdropDeviceTest.kt` — **卡点 A 所在**
- `tools/check_androidtest_signatures.py` — 表达式体闸门
- `tools/tests/test_summarize_glass_evidence.py`（+3 项）

**证据文件（本轮下载，未入仓）**
- `D:\VIBEcoding\.artifacts\final92\` — #92 API33：`api33-summary.json`（23 项全 OK）、
  `error-summary.json`（掉线原因）、`instrumentation-viewer.txt`（卡点 A 完整栈）、
  `verdict.txt`、`viewer-exit.txt`
- `D:\VIBEcoding\.artifacts\final91\` — #91 API33：`error-summary.json`、`verdict.txt`

---

## 9. 复现命令

```bash
# 看某轮结论
gh run view <run-id> --json status,conclusion,jobs -q '.jobs[] | "\(.name) \(.conclusion)"'

# 只看失败步骤的日志
gh run view --job=<job-id> --log-failed

# 下载模拟器证据
gh run download <run-id> -n Tabula-liquid-glass-emulator-API33 -D .artifacts/x

# 本地跑 tools 单测
cd TabulaV3-glass-topbar && python -m unittest discover -s tools/tests -v

# 本地跑 androidTest 签名闸门
python tools/check_androidtest_signatures.py
```
