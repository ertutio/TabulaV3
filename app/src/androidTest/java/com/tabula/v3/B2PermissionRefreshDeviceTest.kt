package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.SwipeStyle
import com.tabula.v3.permission.MediaPermissionPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PR72 后续（2026-11-14）：真实 MainActivity 权限刷新接线的设备级验证——
 * 权限变化 → mediaAccessRevision 更新 → 扫描重启 → 当前界面旧结果拒绝
 * （PR50 语义的设备级接线验证）。预期 case 数 = **3**（同一类三方法，三次
 * 独立 instrumentation，宿主编排——run-cloud-ui-probe.sh
 * "B2 permission-refresh wiring three-step verification" 段，各步 OK(1) 精确门）。
 *
 * 生产接线（读码实证，本类验证的信息流）：
 * onResume → checkPermission(foregroundRefresh=true)（MainActivity.kt:342-352）
 * → applyAccessLevel（:395-407）：级别变化（NONE/PARTIAL/FULL 互切）或级别不变
 * +带权限回前台 → mediaAccessRevision++ → TabulaApp LaunchedEffect(revision,...)
 * 键控重启（取消在途扫描）→ loadAndApplyMediaSnapshot（MainActivity.kt:576-620）
 * → apply 提交 allImages → DeckScreen 快照向可见消费面同步（DeckScreen.kt:720+）。
 * 应用无 ContentObserver（app/src/main 全库 grep 零命中）：会话内权限/媒体变化
 * 在生产侧的唯一重扫触发 = ON_RESUME 修订重查（+ 权限对话框回调路径）。
 *
 * 三步场景（进程边界对齐 #52 口径：pm revoke 系统强停 / pm grant 不杀进程）：
 * 步骤1 [fullDeckShowsFixturesInjectsLateMediaAndRecordsPid]：FULL deck 地面真值
 *   （恰 2 夹具）→ 会话内 shell 注入晚到媒体 → 当前界面旧集合保持（无重扫触发，
 *   晚到媒体不入当前界面——awaitGone 界面面 + MediaStore 行在库双面）→ 进程
 *   PID 落盘（跨进程死亡核对基准）。
 * 步骤2（宿主 pm revoke——**in-process revoke 会杀死 instrumentation**，
 *   CI 41706417934 双 API 实证口径，ForegroundPermissionUiTest 同登记）
 *   [revokedRelaunchShowsGateAndOldSetNeverReappears]：新进程恢复 = REVOKE 后
 *   语义——权限门控屏（PermissionRequestScreen"授权访问照片"）呈现；旧集合
 *   3 名（a1/a2/late）+ deck 标记在组合观察窗内**不得重现**（应用包窗口有效性
 *   门 + 全标签缺席，Phase2 同窗观察纪律）；MediaStore 集合面 3 行仍在库
 *   （不可见 = 权限语义非删除）。
 * 步骤3 [inSessionGrantUpgradeRerunsScanOnSameProcess]：同进程会话内授权升级
 *   接线——无权限启动 = 门控屏 → **pm grant（grant 不杀进程，#52 口径；
 *   应用前台持门控时授权，无后台窗口混淆）**→ Home→am start（onResume 修订
 *   重查，applyAccessLevel **级别变更分支** revision++）→ deck 新池提交
 *   （生产批次日志 currentBatch 行含 2 夹具行 id 的池集证据 + 卡节点可见）
 *   → 全程 pidof 恒等（同进程——会话内权限变化的真实接线证据）。
 *
 * 如实登记（确定性边界，与执行指令口径一致）：
 * - "在途扫描被新键取消、旧读取晚返回不覆盖新权限态"的严格竞态形态设备层
 *   不可确定性构造（生产 load 无注入点、自然扫描毫秒级不可控）——该语义由
 *   单测级 MediaSnapshotLoadRaceTest（PR50）+ FullPartialFullPhase3Test 受控
 *   竞争门（判据 10，真实生产链 NonCancellable 晚返回形态）覆盖；本类验证
 *   真实接线的端到端信息流终态。
 * - 会话内权限集合变化以 **grant 升级（NONE→FULL）**为载体：revoke 存储权限
 *   系统强停为平台行为（不可同进程，CI 41706417934 实证）；FULL 持有下
 *   revoke VISUAL_USER_SELECTED 级别不变（FULL 优先，MediaPermissionPolicy）
 *   不触发 revision——设计示例形态无语义，如实登记；PARTIAL 的真实"用户选中
 *   集合"不可经 pm 确定性夹具化（ForegroundPermissionUiTest 既有登记口径）。
 * - NONE→FULL 时 hasPermission false→true 使 TabulaApp 首次组合——首次加载与
 *   revision 递增在设备层叠加、无法分离二者贡献（revision 键控取消语义由
 *   单测与 Phase3 竞争门覆盖；本步可断言的是级别变更分支的端到端接线）。
 *
 * 夹具纪律：全部 shell 身份（base64 **文本**通道 on-device 落盘 + scan_file
 * 入库 + 广播兜底 + 行截止轮询 + shell 行核对，FullPartialFullStateContract
 * ensureG3Fixture 同款——字节不经 UiAutomation，run 42476083194/42477634711
 * 截断实证口径）；每方法用例末自清 + @After 兜底（幂等）；probe 侧
 * dependency-skip + 按名兜底清理。状态文件跨进程死亡持久（phase1Pid）。
 */
@RunWith(AndroidJUnit4::class)
class B2PermissionRefreshDeviceTest {
    private val TAG = "B2PermRefresh"
    private val ui get() = PartialSelectionUi
    private val ctx get() = ui.ctx

    // ==================== 夹具契约（shell 身份，Pictures/B2PermissionRefreshFixtures/） ====================

    private val FIXTURE_DIR = "Pictures/B2PermissionRefreshFixtures/"
    private val FIXTURE_ABS_DIR = "/sdcard/Pictures/B2PermissionRefreshFixtures"
    private val STATE_FILE_NAME = "b2-permission-refresh-state.json"

    /** 步骤1 FULL 集合 2 夹具（deck 旧集合基准）。 */
    private val A1_NAME = "b2pr-a1.png"
    private val A2_NAME = "b2pr-a2.png"

    /** 步骤1 会话内晚到注入媒体（步骤2 断言其也不重现）。 */
    private val LATE_NAME = "b2pr-late.png"

    /** 步骤3 自有夹具 2 张（与步骤1/2 无共享，ground truth 恰 2 行）。 */
    private val C1_NAME = "b2pr-c1.png"
    private val C2_NAME = "b2pr-c2.png"

    /** mtime（touch -t）：同落 2026-11-14，互相错开（a1<a2<late<c1<c2）。 */
    private val A1_TOUCH_TIME = "202611140900"
    private val A2_TOUCH_TIME = "202611140930"
    private val LATE_TOUCH_TIME = "202611141000"
    private val C1_TOUCH_TIME = "202611141100"
    private val C2_TOUCH_TIME = "202611141200"

    /** 权限门控屏按钮文本（PermissionRequestScreen，MainActivity.kt:1906）。 */
    private val GATE_BUTTON = "授权访问照片"

    /**
     * 96x96 纯绿 PNG 的 base64（与 FullPartialFullStateContract.G3_PNG_BASE64
     * 同款同源——内容不承载断言语义，身份 = 名+行；字节不经 UiAutomation，
     * base64 文本通道 CI 长期实证）。
     */
    private val PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAIAAABt+uBvAAABC0lEQVR4nO3OMQ0AIAAEsfdvGhyw9gaSCuh29r34QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UHbBY393g69gixzAAAAAElFTkSuQmCC"

    /** 生产全局批次日志锚（DeckScreen.logBatchRows，Log.v DeckScreen）。 */
    private val BATCH_LOG_ANCHOR = "currentBatch<-["

    /**
     * 步骤1 成功后保持夹具（a1/a2/late 行在库 = 步骤2 缺席断言与集合面核对
     * 的依赖，Phase1 @After 条件清理同款纪律）——置位后本 instrumentation 的
     * @After 跳过清理；步骤2 用例末自清 3 夹具、步骤3 自管 c1/c2，各自的
     * @After 走全量幂等兜底（未置位路径 = 失败兜底清理）。
     */
    private var keepFixturesForNextStep = false

    @Before fun setup() {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
    }

    // ==================== 共享原语（ensureG3Fixture 模式 + Phase3 边界 2 模式） ====================

    private fun stateFile(): File = File(
        ctx.getExternalFilesDir(null) ?: ctx.filesDir, STATE_FILE_NAME
    )

    private fun shellRowQuery(name: String): String = ui.shellScript(
        "content query --uri content://media/external/images/media " +
            "--projection _display_name:relative_path " +
            "--where \"_display_name='$name'\" 2>&1"
    )

    private fun awaitShellRow(name: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val out = shellRowQuery(name)
            if ("Row: 0" in out && "_display_name=$name" in out) return true
            SystemClock.sleep(150)
        }
        return false
    }

    private fun assertShellRowExists(name: String) {
        val output = shellRowQuery(name)
        assertTrue(
            "shell 行核对失败：$name 必须在库（相对路径=${FIXTURE_DIR.trimEnd('/')}）：$output",
            output.contains("Row: 0") &&
                output.contains("_display_name=$name") &&
                output.contains("relative_path=${FIXTURE_DIR.trimEnd('/')}")
        )
    }

    /**
     * shell 身份夹具注入（幂等）：按名+路径预清 → shellScript 落盘（mkdir +
     * base64 解码 + touch -t）→ scan_file 入库（宿主管线同款机制）→ 截止轮询
     * 行出现，未入库走 MEDIA_SCANNER_SCAN_FILE 广播兜底再轮询 → shell 行核对。
     * 行所有权 = shell（文件由 shell UID 经 FUSE 落盘）——与宿主 adb push 同身份。
     */
    private fun injectFixture(name: String, touchTime: String) {
        ui.shellScript(
            "content delete --uri content://media/external/images/media " +
                "--where \"_display_name='$name' AND relative_path='$FIXTURE_DIR'\" 2>&1"
        )
        ui.shellScript("rm -f $FIXTURE_ABS_DIR/$name 2>&1")
        val written = ui.shellScript(
            "mkdir -p $FIXTURE_ABS_DIR && echo $PNG_BASE64 | base64 -d > $FIXTURE_ABS_DIR/$name && " +
                "touch -t $touchTime $FIXTURE_ABS_DIR/$name && ls -l $FIXTURE_ABS_DIR/$name 2>&1"
        )
        check(written.contains(name)) { "夹具落盘失败（ls 无回显）：$name：$written" }
        ui.shellScript(
            "content call --uri content://media/ --method scan_file " +
                "--arg $FIXTURE_ABS_DIR/$name 2>&1"
        )
        if (!awaitShellRow(name, timeoutMs = 10000)) {
            ui.shellScript(
                "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE " +
                    "-d file://$FIXTURE_ABS_DIR/$name 2>&1"
            )
            check(awaitShellRow(name, timeoutMs = 10000)) {
                "夹具 scan 入库失败（scan_file + 广播兜底均未产生行）：$name：${shellRowQuery(name)}"
            }
        }
        assertShellRowExists(name)
        Log.i(TAG, "fixture injected: $name (touch=$touchTime, shell identity row confirmed)")
    }

    /** shell 精确删除夹具（按名+本套件路径），返回行是否已消失。 */
    private fun shellDeleteFixture(name: String): Boolean {
        val query = ui.shellScript(
            "content query --uri content://media/external/images/media " +
                "--projection _id --where \"_display_name='$name'\" 2>&1"
        )
        if ("Row: 0" !in query) return true
        ui.shellScript(
            "content delete --uri content://media/external/images/media " +
                "--where \"_display_name='$name' AND relative_path='$FIXTURE_DIR'\" 2>&1"
        )
        val after = ui.shellScript(
            "content query --uri content://media/external/images/media " +
                "--projection _id --where \"_display_name='$name'\" 2>&1"
        )
        return "Row: 0" !in after
    }

    /** 按名+路径删除全部夹具 + rm 文件（@After 兜底，幂等）。 */
    private fun cleanupAllFixtures() {
        for (name in listOf(A1_NAME, A2_NAME, LATE_NAME, C1_NAME, C2_NAME)) {
            val gone = shellDeleteFixture(name)
            Log.i(TAG, "cleanup $name gone=$gone")
        }
        ui.shellScript("rm -rf $FIXTURE_ABS_DIR 2>&1")
    }

    /** shell 面全库名单（无权限侧的集合面地面真值；CRLF 容忍）。 */
    private fun shellGalleryNames(): List<String> =
        ui.shellScript(
            "content query --uri content://media/external/images/media " +
                "--projection _display_name 2>&1"
        ).lineSequence()
            .filter { it.startsWith("Row:") }
            .mapNotNull { row ->
                Regex("_display_name=(.+?)\\s*$")
                    .find(row.trimEnd('\r'))?.groupValues?.get(1)
            }
            .toList()

    /** 夹具行 _id（shell 面解析；批次日志按行 id 比对——卷限定 uri 形式无关）。 */
    private fun fixtureRowId(name: String): String {
        val out = ui.shellScript(
            "content query --uri content://media/external/images/media " +
                "--projection _id --where \"_display_name='$name'\" 2>&1"
        )
        return Regex("_id=(\\d+)").find(out)?.groupValues?.get(1)
            ?: error("夹具行 _id 解析失败：$name：$out")
    }

    /** 当前应用进程 pid（pidof；无输出 = 进程已死，如实报错）。 */
    private fun appPid(): Int {
        val out = ui.shellScript("pidof ${ctx.packageName} 2>&1").trim()
        return out.toIntOrNull() ?: error("pidof 无有效输出（进程已死或未起）：'$out'")
    }

    /** 任一标签节点可见的截止轮询（返回首个命中标签；失败留证）。 */
    private fun awaitAnyNode(labels: List<String>, timeoutMs: Long, tag: String): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = ui.ins.uiAutomation.rootInActiveWindow
            for (label in labels) {
                if (ui.find(root, label) != null) return label
            }
            SystemClock.sleep(150)
        }
        ui.dumpTree("timeout-$tag")
        ui.saveScreenshot("timeout-$tag")
        error("no node visible for $labels ($tag)")
    }

    /**
     * 组合观察窗（Phase2 同窗观察纪律）：windowMs 内逐次读数——应用包窗口
     * 有效性计数（appWindowOk）+ 全标签缺席不变量（任一出现即留证失败）。
     * 窗末有效性门：appWindowOk > 0（应用窗口从未成立 = 观察无效）。
     */
    private fun assertNeverVisible(labels: List<String>, windowMs: Long, tag: String) {
        val deadline = SystemClock.uptimeMillis() + windowMs
        var appWindowOk = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val root = ui.ins.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString() == ctx.packageName) appWindowOk++
            for (label in labels) {
                if (ui.find(root, label) != null) {
                    ui.dumpTree("unexpected-$tag-$label")
                    ui.saveScreenshot("unexpected-$tag-$label")
                    error("旧集合不得重现：$label 在观察窗内出现（$tag）")
                }
            }
            SystemClock.sleep(200)
        }
        assertTrue(
            "观察窗内必须存在被测应用窗口（有效性门，appWindowOk=$appWindowOk，$tag）",
            appWindowOk > 0
        )
        Log.i(TAG, "$tag absence window ok: appWindowOk=$appWindowOk labels=${labels.size}")
    }

    // ==================== 步骤1：FULL deck + 会话内晚到注入 + PID 落盘 ====================

    @Test
    fun fullDeckShowsFixturesInjectsLateMediaAndRecordsPid() {
        // 前置：FULL 已授（宿主 pm grant），VISUAL_USER_SELECTED 无授权（pm clear
        // 天然清理 + 宿主兜底 revoke）；级别=FULL。引导态种子（pm clear 后）。
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        )
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.FULL,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )
        AppPreferences(ctx).apply {
            hasCompletedOnboarding = true
            hasAcknowledgedDisclaimer = true
            swipeStyle = SwipeStyle.SHUFFLE
        }
        AppPreferences.getInstance(ctx).clearAllAlbumCleanupState()

        // ---- FULL 集合建立（2 夹具）+ 地面真值（恰 2 行，无重复无缺失）----
        injectFixture(A1_NAME, A1_TOUCH_TIME)
        injectFixture(A2_NAME, A2_TOUCH_TIME)
        val visibleList = FullPartialFullStateContract.visibleDisplayNameList(ctx)
        val visibleSet = visibleList.toSet()
        assertTrue(
            "FULL 集合面不得有重复行（list=${visibleList.size} set=${visibleSet.size}）",
            visibleList.size == visibleSet.size
        )
        assertEquals(
            "FULL 地面真值：可见集合恰为 2 夹具（无多余无缺失）；实际=$visibleSet",
            setOf(A1_NAME, A2_NAME), visibleSet
        )
        assertShellRowExists(A1_NAME)
        assertShellRowExists(A2_NAME)

        // ---- 真实启动 → deck 渲染（旧集合基准：2 夹具在窗口可见）----
        ui.launch()
        ui.waitFor(ui.DECK_MARKER)
        val seenCard = awaitAnyNode(listOf(A1_NAME, A2_NAME), timeoutMs = 30000, tag = "t1-deck-cards")
        Log.i(TAG, "t1 deck rendered with old set (card node seen: $seenCard)")

        // ---- PID 落盘（跨进程死亡核对基准；应用与 instrumentation 同进程）----
        val pid = android.os.Process.myPid()
        stateFile().writeText(
            JSONObject()
                .put("phase1Pid", pid)
                .put("oldSetNames", org.json.JSONArray(listOf(A1_NAME, A2_NAME)))
                .put("lateName", LATE_NAME)
                .put("fixtureDir", FIXTURE_DIR)
                .toString()
        )
        assertTrue(stateFile().length() > 0)
        Log.i(TAG, "t1 pid recorded: $pid")

        // ---- 会话内晚到注入（shell 身份；无重扫触发）→ 行在库 + 集合面恰 3 行 ----
        injectFixture(LATE_NAME, LATE_TOUCH_TIME)
        assertShellRowExists(LATE_NAME)
        val visibleAfterLate = FullPartialFullStateContract.visibleDisplayNameList(ctx)
        assertEquals(
            "晚到注入后 MediaStore 集合面恰为 2 夹具+late（无多余无缺失）",
            setOf(A1_NAME, A2_NAME, LATE_NAME), visibleAfterLate.toSet()
        )

        // ---- 当前界面旧集合保持：晚到媒体不得入当前界面（应用无 ContentObserver，
        //      会话内注入唯一重扫触发 = ON_RESUME，本步未触发 → 界面面确定性缺席）----
        ui.awaitGone(LATE_NAME, timeoutMs = 5000)
        ui.waitFor(ui.DECK_MARKER)
        // 步骤1 成功：夹具保留（行在库 = 步骤2 缺席断言与集合面核对的依赖），
        // @After 据此跳过清理（Phase1 条件清理同款纪律）。
        keepFixturesForNextStep = true
        Log.i(TAG, "t1 complete: late media kept out of current UI (no rescan trigger), old set stable, fixtures kept for step2")
    }

    // ==================== 步骤2（宿主 revoke 强停后）：新进程 = REVOKE 后语义 ====================

    @Test
    fun revokedRelaunchShowsGateAndOldSetNeverReappears() {
        // 依赖门（coord/notes-B2.md §7.3）：步骤1 未落盘 PID 则本步拒绝执行。
        val state = stateFile()
        check(state.exists() && state.length() > 0) {
            "依赖未满足：步骤1 未完成——状态文件不存在（本步拒绝执行）"
        }
        val phase1Pid = runCatching { JSONObject(state.readText()).getInt("phase1Pid") }
            .getOrElse { -1 }
        check(phase1Pid > 0) {
            "依赖未满足：步骤1 未完成——状态缺少有效 phase1Pid（本步拒绝执行）"
        }
        // REVOKE 后语义前置：两权限均无授权，级别 = NONE。
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        )
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.NONE,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )
        // 新进程断言：revoke 系统强停 → 本步运行在与步骤1 不同的进程。
        assertNotEquals(
            "步骤2 必须运行在 revoke 强停之后的新进程（宿主 pm revoke）",
            phase1Pid, android.os.Process.myPid()
        )
        Log.i(TAG, "t2 new process confirmed: phase1Pid=$phase1Pid myPid=${android.os.Process.myPid()}")

        // ---- 重启恢复：权限门控屏呈现（REVOKE 后语义：回收站/图库不可见，门控接管）----
        ui.launch()
        ui.waitFor(GATE_BUTTON, timeoutMs = 30000)
        Log.i(TAG, "t2 permission gate screen rendered after revoke relaunch")

        // ---- 旧集合不重现：组合观察窗（应用包窗口有效性 + 全标签缺席）----
        // 旧集合 = 步骤1 的 2 夹具 + 晚到媒体 + deck 状态标记——权限门控下
        // TabulaApp 不组合，任何旧结果出现在窗口即失败（留证）。
        assertNeverVisible(
            listOf(A1_NAME, A2_NAME, LATE_NAME, ui.DECK_MARKER),
            windowMs = 10000,
            tag = "t2-post-revoke"
        )

        // ---- 集合面仍在库（shell 面 3 行）：不可见 = 权限语义非删除 ----
        for (name in listOf(A1_NAME, A2_NAME, LATE_NAME)) {
            assertShellRowExists(name)
        }
        Log.i(TAG, "t2 complete: gate shown, old set never reappeared, shell rows intact")

        // ---- 用例末自清（3 夹具行+文件；@After 另有兜底）----
        for (name in listOf(A1_NAME, A2_NAME, LATE_NAME)) {
            val gone = shellDeleteFixture(name)
            Log.i(TAG, "t2 cleanup $name gone=$gone")
            assertTrue("步骤2 用例末夹具删除后必须消失：$name", gone)
        }
        ui.shellScript("rm -f $FIXTURE_ABS_DIR/$A1_NAME $FIXTURE_ABS_DIR/$A2_NAME $FIXTURE_ABS_DIR/$LATE_NAME 2>&1")
        keepFixturesForNextStep = false
    }

    // ==================== 步骤3：同进程会话内授权升级接线（grant 不杀进程） ====================

    @Test
    fun inSessionGrantUpgradeRerunsScanOnSameProcess() {
        // 前置：两权限均无授权（宿主 revoke 后未 grant），级别 = NONE。
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        )
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.NONE,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )
        // 引导态种子（防御性，幂等——授权升级后 deck 直达，不进引导流）。
        AppPreferences(ctx).apply {
            hasCompletedOnboarding = true
            hasAcknowledgedDisclaimer = true
            swipeStyle = SwipeStyle.SHUFFLE
        }
        AppPreferences.getInstance(ctx).clearAllAlbumCleanupState()

        // ---- 自有夹具 2 张（shell 身份注入无需应用权限）+ 集合面地面真值（恰 2 行）----
        injectFixture(C1_NAME, C1_TOUCH_TIME)
        injectFixture(C2_NAME, C2_TOUCH_TIME)
        val galleryNames = shellGalleryNames()
        assertTrue(
            "步骤3 集合面不得有重复行（list=${galleryNames.size} set=${galleryNames.toSet().size}）",
            galleryNames.size == galleryNames.toSet().size
        )
        assertEquals(
            "步骤3 集合面地面真值：恰为 2 夹具（无多余无缺失）；实际=${galleryNames.toSet()}",
            setOf(C1_NAME, C2_NAME), galleryNames.toSet()
        )
        val c1Id = fixtureRowId(C1_NAME)
        val c2Id = fixtureRowId(C2_NAME)

        // ---- 无权限启动 = 权限门控屏；PID 基准（应用与 instrumentation 同进程）----
        ui.launch()
        ui.waitFor(GATE_BUTTON, timeoutMs = 30000)
        val pid0 = android.os.Process.myPid()
        assertEquals("应用必须运行在 instrumentation 同进程（pidof 恒等）", pid0, appPid())
        Log.i(TAG, "t3 gate shown, pid baseline=$pid0")

        // ---- 会话内授权（grant 不杀进程，#52 口径）：应用前台持门控时授权，
        //      无后台窗口混淆；权限变化对应用不可见（无监听），直至下一次 onResume ----
        ui.shellScript("pm grant ${ctx.packageName} ${Manifest.permission.READ_MEDIA_IMAGES} 2>&1")
        assertEquals(
            "会话内 pm grant 必须生效",
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.FULL,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )
        assertEquals(
            "grant 不杀进程（#52 口径）：授权后进程必须存活且 pid 恒等",
            pid0, appPid()
        )
        Log.i(TAG, "t3 in-session grant applied, process alive (pid=$pid0), level=FULL")

        // ---- Home → am start：onResume 修订重查（applyAccessLevel 级别变更分支
        //      revision++）→ hasPermission 翻转 → deck 新池提交 ----
        ui.shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(1000)
        ui.launch()

        // 截止轮询（60s）：①生产批次日志 currentBatch 行含 2 夹具行 id（池集证据，
        // 重扫描已提交——Phase3 边界 2 同款按行 id 比对，卷限定形式无关）+
        // ②门控按钮自窗口消失（权限翻转生效）+ ③夹具卡节点可见（新内容到达消费面）。
        val deadline = SystemClock.uptimeMillis() + 60000
        var batchConfirmed = false
        var gateGone = false
        var nodeSeen = false
        var lastBatchLine = ""
        while (SystemClock.uptimeMillis() < deadline && !(batchConfirmed && gateGone && nodeSeen)) {
            val root = ui.ins.uiAutomation.rootInActiveWindow
            val appWindow = root?.packageName?.toString() == ctx.packageName
            if (appWindow && ui.find(root, GATE_BUTTON) == null) {
                if (!gateGone) Log.i(TAG, "t3 gate dismissed (permission flip rendered)")
                gateGone = true
            }
            if (appWindow && !nodeSeen) {
                nodeSeen = ui.find(root, C1_NAME) != null || ui.find(root, C2_NAME) != null
            }
            if (!batchConfirmed) {
                lastBatchLine = ui.shell("logcat -d -s DeckScreen").lineSequence()
                    .lastOrNull { it.contains(BATCH_LOG_ANCHOR) } ?: ""
                val batchIds = lastBatchLine.substringAfter(BATCH_LOG_ANCHOR, "")
                    .split(",")
                    .mapNotNull { entry ->
                        val id = entry.trim().substringBeforeLast(":").substringAfterLast("/", "")
                        if (id.matches(Regex("\\d+"))) id else null
                    }
                    .toSet()
                if (batchIds.containsAll(setOf(c1Id, c2Id))) {
                    batchConfirmed = true
                    Log.i(TAG, "t3 batch log contains both fixture ids: $batchIds")
                }
            }
            SystemClock.sleep(250)
        }
        if (!(batchConfirmed && gateGone && nodeSeen)) {
            ui.dumpTree("timeout-t3-rescan")
            ui.saveScreenshot("timeout-t3-rescan")
            error(
                "步骤3 授权升级重扫观察超时：batchConfirmed=$batchConfirmed " +
                    "gateGone=$gateGone nodeSeen=$nodeSeen lastBatchLine=$lastBatchLine " +
                    "expectedIds=[$c1Id,$c2Id]"
            )
        }

        // ---- 全程同进程终验：授权升级接线未经历进程死亡（对比 revoke 强停）----
        assertEquals(
            "会话内授权升级全程必须同进程（pidof 恒等）",
            pid0, appPid()
        )
        Log.i(TAG, "t3 complete: in-session grant upgrade wired to rescan on same process (pid=$pid0)")

        // ---- 用例末自清（2 夹具行+文件；@After 另有兜底）----
        for (name in listOf(C1_NAME, C2_NAME)) {
            val gone = shellDeleteFixture(name)
            Log.i(TAG, "t3 cleanup $name gone=$gone")
            assertTrue("步骤3 用例末夹具删除后必须消失：$name", gone)
        }
        ui.shellScript("rm -f $FIXTURE_ABS_DIR/$C1_NAME $FIXTURE_ABS_DIR/$C2_NAME 2>&1")
    }

    /**
     * 残留兜底（幂等，条件清理——Phase1 同款纪律）：步骤1 成功置位
     * [keepFixturesForNextStep] 时跳过（a1/a2/late 行在库是步骤2 依赖）；
     * 其余情况全量按名+路径删除（步骤2/3 用例末自清已删的行按缺席成立，
     * 失败路径由此兜底），防死行残留污染同宿主后续段。
     */
    @After fun cleanup() {
        if (keepFixturesForNextStep) {
            Log.i(TAG, "cleanup skipped: step1 fixtures kept for step2 absence assertions")
            return
        }
        // 状态文件保留（跨进程核对基准由 probe pull 留档）；本兜底只清媒体行。
        cleanupAllFixtures()
    }
}
