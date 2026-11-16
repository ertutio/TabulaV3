# Review — TC58 卡点定向修正（ResetAppSingletonsPlugin 测试基建）

- 对象：ertutio/TabulaV3 PR #80，分支 `test/tc58-hash-recovery-2026-11-15`，提交 `5d6331b`（差分复审，只读）
- 复审员：独立复审（轮 3 落地后的差分复核）
- 日期：2026-11-15
- 结论：**PASS**

## 1. 提交内容（`git show 5d6331b --stat`）

恰 2 个新增文件（均为 A / 纯插入，55 行）：

- `app/src/test/java/com/tabula/v3/testsupport/ResetAppSingletonsPlugin.kt`（54 行）
- `app/src/test/resources/META-INF/services/org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin`（1 行）

无修改/删除项。工作树 clean、无 untracked 文件；全仓（除 build 产物外）搜不到 `StubProbeTest*` —— 无诊断残留进入 git。

## 2. 插件语义与 KDoc 技术声明

`ResetAppSingletonsPlugin.kt` 与咨询验证版语义一致：

- `RuntimeEnvironment.getApplication() ?: return` 非空守卫（首个测试方法 no-op）+ `app.javaClass.classLoader ?: return`；
- 7 个单例 FQN 清单，与 `app/src/main` 实际源码逐一核对：7 个类均在 companion object 中声明 `private var instance: X? = null`（AppPreferences.kt:1229、ImageHashCache.kt:50、AlbumCleanupEngine.kt:122、AlbumManager.kt:54、AlbumUsageTracker.kt:58、RecommendationEngine.kt:320、RecycleBinManager.kt:64，后者显式 @Volatile）；
- 经上一方法 Application 的 loader `loadClass` → 外层类 `getDeclaredField("instance")` → `set(null, null)` 静态置空；
- 双层 try/catch（内层逐类、外层整体）吞 Throwable 兜底 no-op。

KDoc 两点关键技术声明经字节码抽验：

- **companion @Volatile 编译为外层类静态字段**：`javap -p` 实测 `app/build/tmp/kotlin-classes/debug` 下 `AppPreferences` 外层类含 `private static volatile AppPreferences instance;`，`AppPreferences$Companion` 无 instance 字段（AlbumManager 亦同）。对 Companion 反射确实找不到，插件取外层类正确。
- **onSetupApplicationState 在 clearEnvironment 之前**：`javap -c` 反编译 gradle 缓存中 robolectric-4.12.2.jar 的 `org.robolectric.android.internal.AndroidTestEnvironment`，`setUpApplicationState` 字节码序：offset 59 `invokeinterface TestEnvironmentLifecyclePlugin.onSetupApplicationState` → offset 71 `invokespecial clearEnvironment` → offset 419 才重设 application supplier；且 `clearEnvironment` 内 `putstatic RuntimeEnvironment.application = null`。即插件钩子触发时"上一方法的 Application 仍被持有、其 classLoader 即 sandbox loader"成立，首个方法 no-op 守卫也成立。

KDoc 其余引用抽验：`AlbumCleanupEngine.kt:133-134` 确为构造器属性初始化内的 `AppPreferences.getInstance(context)` / `ImageHashCache.getInstance(context)`（测试侧不可绕过的路径）；`AlbumCleanupForegroundRefreshTest` :177 即直接 `AlbumCleanupEngine(context)` 构造行。声明准确。

## 3. 服务注册

服务文件内容恰为实现类 FQN 单行 `com.tabula.v3.testsupport.ResetAppSingletonsPlugin`（LF 结尾，无多余空白），与 Kotlin 文件包名/类名一致。`app/src/test/resources/robolectric.properties` 存在但 `git show 5d6331b -- <该路径>` 输出为空 —— 本提交未改动它。

## 4. 范围纪律

提交仅 2 文件且全部位于 `app/src/test`（testsupport + resources）；无 `app/src/main` 生产码改动；无既有测试文件改动（全为纯插入）。符合"测试基建层"的定位。

## 5. 插件风险评估

- **清单手工维护**：未来新增应用层单例若不入清单，该单例的跨类 static 存活缺口依旧（症状与本次相同：CI 红）。KDoc 已明示该纪律；属可接受的已知边界。
- **字段改名静默跳过**：`getDeclaredField("instance")` 抛 `NoSuchFieldException` 被内层 catch 吞掉，单例被静默跳过，不产生显式失败信号；失效模式=原始症状复发，可经 CI（套件顺序可复现）检出，非静默数据损坏，且仅影响测试环境、无生产影响。
- **判定：接受**。理由：测试基建专用、失败模式可检出、修复成本（清单补一行）远低于侵入 Robolectric 配置或生产码；咨询者已 433/433 ×2 实证、轮 3 CI 绿。
- **登记建议（非阻塞，建议后续跟进）**：补一个清单完整性守卫测试——遍历 7 个 FQN 断言 `getDeclaredField("instance")` 可解析，使"改名/删类/漏登记"在提交时 fail-fast 而非静默跳过。

## 6. CI 元数据

`gh run view 43703642885`：`headSha=5d6331b13eb5818ff0737f329c015b4075b74284`、`conclusion=success`、`headBranch=test/tc58-hash-recovery-2026-11-15`、3 jobs（build + device 36 + device 37）全绿。与既有工件级核（Tc58 2/2、:177 类 16/16）一致。

## 结论

**PASS** —— 差分范围恰 2 个新增测试基建文件、语义与咨询定案一致、两处关键技术声明经字节码实证、无范围外改动、CI 元数据吻合。登记建议见第 5 节（清单完整性守卫测试，非阻塞）。
