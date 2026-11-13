package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.FileOperationManager
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.RecycleBinManager
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.FullPartialFullStateContract.assertShellRowExists
import com.tabula.v3.FullPartialFullStateContract.shellDeleteFixture
import com.tabula.v3.FullPartialFullStateContract.visibleDisplayNameList
import com.tabula.v3.FullRecoveryStateContract.rebuildRow
import com.tabula.v3.permission.MediaPermissionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * T07 FULL→PARTIAL→FULL 三阶段验证——Phase FULL 恢复（判据 8–10）。
 *
 * 前置（宿主编排，#52 口径）：pm grant READ_MEDIA_IMAGES（grant 不杀进程）→
 * am force-stop（显式重启）→ 本阶段新 instrumentation 进程；应用数据保留。
 *
 * 断言链（判据逐条）：
 * 8. 地面真值：可见恰 = {g1,g2,g3,n1,b1,b2}+哨兵存在性（无重复：list==set；无缺失：
 *    shell 7 行核对）；级别=FULL。
 * 9. 已处理保持（驱动 UI 前的记录读回）：断点 = **真实 UI 自保存状态 (group,0)**
 *    原样（PARTIAL 期间只读未触碰——Phase1 不再覆盖的边界 3 基准）、
 *    acknowledged={g1键}、回收站=[g1键]、组键 [g1,g2,g3] 无重复无丢失、未永久完成。
 * 9-边界3（生产续接契约，2026-11-14）：生产入口 getCheckpointBatch 的放弃规则
 *    （AlbumCleanupEngine.kt:800-804）：断点保存组内任一成员已 acknowledged →
 *    clearCheckpoint + 返回 null（"明确删除已缩短可操作成员，旧索引无法判断保存
 *    与删除先后。安全从该原组剩余行重开，不能盲目平移而漏掉尚未浏览的成员"）。
 *    UI 自保存 ([G],0) 含已处理 g1 → 断言：返回 null + 断点被清除 + 组记录/已处理
 *    不受放弃误伤；随后安全重启批次 = 剩余成员持久键序 **[g2,g3]**（≥2 有序未处理
 *    成员：成员顺序可断言，g1 经 acknowledged 过滤不交付）。预期值全部来自生产
 *    契约明文，非被测算法重算。
 * 10. 旧快照晚到不覆盖：以真实生产链 loadAndApplyMediaSnapshot 重演 PR50
 *     MediaSnapshotLoadRaceTest.lateReturningOldReadReturnsButNeverCommits 的晚到
 *     形态——旧扫描滞留在 PARTIAL 时代投影 {b1,b2}（底层读取 NonCancellable 闸门，
 *     晚返回形态成立）、新扫描走默认真实生产 load 并提交；断言：旧读取确实返回、
 *     提交只归新扫描、新扫描 apply 到的投影 = 当前真实全池投影（7 行 − 回收站 g1
 *     = 6 行，真实数据）、取消不落 error 态。
 * 9'. UI 续接 + 后续处理（真实月份入口 → 生产安全重启路径）→ deck 当前卡 = g2、
 *     g1 不在 deck；上滑处理 g2（续接批次实际可操作）→ 下一卡 = g3（持久键序的
 *     成员顺序在真实 deck 上成立）→ 生产撤销窗口提交（acknowledged={g1,g2}、
 *     回收站=[g1,g2]）。
 * 10'. 最终一致性：可见集/acknowledged/回收站/组键/月份归属与第 9 条一致（最终
 *      UI/查询状态不受晚到旧快照影响）。
 * 边界 2（真实接线验证，2026-11-14 协调者定稿）：判据 10' 之后追加两循环——会话内
 *      shellScript 注入 fpf-n2/fpf-n3（ensureG3Fixture 同款 base64 文本通道）→
 *      Home→2s→am start（ON_RESUME→权限修订重查+重扫描，MainActivity 生产链）→
 *      退出清理（真实 sheet onSwitchToGlobal 清批+重取）→ 生产批次日志池集相等断言
 *      （新成员入池+既有无丢失+回收站行不入池）+ deck 窗口 displayName 节点截止
 *      轮询（洗牌位重试轮）→ 全集无重复 → 哨兵 s1 shell 面存活 → 用例末 shell 清理
 *      n2/n3 行+文件。如实登记：旧读取"晚返回"的确定性构造设备层不可行（生产 load
 *      无注入点）——该语义由单测级 MediaSnapshotLoadRaceTest（PR50）覆盖，本段验证
 *      最终状态正确性；probe 对本类为类级驱动但有 OK(1) 精确计数门（run-cloud-ui-probe.sh
 *      :346/:351），第二方法确定性破门 → 按约束回退并入本方法（probe 未动，详见
 *      coord/notes-B2.md §九）。
 *
 * 套件收尾（@After）：按名+路径 shell 精确删除全部 7 夹具并核对消失（#57 PhaseB
 * 收尾同款），另含边界 2 残留兜底（n2/n3 不在 ALL_NAMES，用例中途失败时兜底删除），
 * 不残留到后续 probe 段。
 */
@RunWith(AndroidJUnit4::class)
class FullPartialFullPhase3Test {
    private val TAG = "FullPartialFullP3"
    private val ui get() = PartialSelectionUi
    private val ctx get() = ui.ctx

    // ==================== 边界 2：会话内注入夹具（宿主不推送，测试侧自管自清） ====================

    /** 边界 2 新成员名（设计指定 fpf-n2；第 2 循环 fpf-n3——均不入 ALL_NAMES/集合断言）。 */
    private val B2_N2_NAME = "fpf-n2.png"
    private val B2_N3_NAME = "fpf-n3.png"

    /**
     * n2/n3 mtime（touch -t）：2026-11-10 10:10 / 12:00——均晚于 g3（202611090900，
     * 原全套最晚）且同落 2026-11（月份桶不因注入漂移）；n3 晚于 n2（第 2 循环"新
     * mtime 最晚"）。
     */
    private val B2_N2_TOUCH_TIME = "202611101010"
    private val B2_N3_TOUCH_TIME = "202611101200"

    /**
     * fpf-n2/fpf-n3 的 base64（与 g3 同款 96x96 纯绿 PNG——内容不承载断言语义，身份 =
     * 名+行；字节不经 UiAutomation，base64 文本通道 CI 长期实证，见
     * FullPartialFullStateContract.ensureG3Fixture 注）。
     */
    private val B2_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAIAAABt+uBvAAABC0lEQVR4nO3OMQ0AIAAEsfdvGhyw9gaSCuh29r34QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UHbBY393g69gixzAAAAAElFTkSuQmCC"

    /** 消费观察截止（每循环）：批次日志收敛（重扫提交）+ deck 节点洗牌位命中。 */
    private val B2_CONSUME_DEADLINE_MS = 90_000L

    /** 生产全局批次重取日志锚（DeckScreen.logBatchRows，batch-site-3 = 首批重取路径）。 */
    private val B2_BATCH_LOG_ANCHOR = "currentBatch<-[batch-site-3]:"

    @Before fun setup() {
        // 依赖门（coord/notes-B2.md §7.3）：Phase2 未成功落盘（含 phase2Pid）则本阶段
        // 拒绝执行——probe 侧配合依赖跳过，不再把级联失败误判为本阶段独立红
        FullPartialFullStateContract.requireDependency(ctx, "phase2Pid", "Phase 2")
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assertEquals(
            "Phase 恢复前置：FULL 必须已恢复",
            PackageManager.PERMISSION_GRANTED,
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        )
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.FULL,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    /**
     * 套件最后阶段：按名+路径精确删除全部 7 个合成夹具并核对消失；另含边界 2 残留
     * 兜底（n2/n3 不在 ALL_NAMES——宿主不推送、用例末自清；用例中途失败路径由此
     * 兜底删除，防死行残留污染后续 probe 段）。
     */
    @After fun cleanup() {
        val stateFile = FullPartialFullStateContract.stateFile(ctx)
        if (!stateFile.exists()) return
        for (name in FullPartialFullStateContract.ALL_NAMES) {
            val gone = shellDeleteFixture(name) { command -> ui.shellScript(command) }
            Log.i(TAG, "cleanup $name gone=$gone")
            assertTrue("夹具删除后必须消失：$name", gone)
        }
        // 边界 2 残留兜底：幂等（未注入过时行本就不存在，删除核对按缺席成立）
        for (name in listOf(B2_N2_NAME, B2_N3_NAME)) {
            val gone = shellDeleteFixture(name) { command -> ui.shellScript(command) }
            Log.i(TAG, "cleanup b2-residual $name gone=$gone")
            assertTrue("边界2夹具删除后必须消失：$name", gone)
        }
        ui.shellScript("rm -rf ${FullPartialFullStateContract.FIXTURE_ABS_DIR}")
    }

    // ==================== 边界 2 原语（ensureG3Fixture 模式复用 + 生产日志观测） ====================

    /**
     * 会话内 shell 身份夹具注入（ensureG3Fixture 同款模式）：按名+路径预清（幂等重跑
     * 安全）→ shellScript 落盘（mkdir + base64 文本解码 + touch -t）→ scan_file 入库
     * （宿主管线同款机制）→ 截止轮询行出现，未入库走 MEDIA_SCANNER_SCAN_FILE 广播兜底
     * 再轮询 → shell 行存在核对。行所有权 = shell（文件由 shell UID 经 FUSE 落盘）。
     */
    private fun injectBoundary2Fixture(name: String, touchTime: String) {
        ui.shellScript(
            "content delete --uri content://media/external/images/media " +
                "--where \"_display_name='$name' AND relative_path='${FullPartialFullStateContract.FIXTURE_DIR}'\" 2>&1"
        )
        ui.shellScript("rm -f ${FullPartialFullStateContract.FIXTURE_ABS_DIR}/$name 2>&1")
        val written = ui.shellScript(
            "mkdir -p ${FullPartialFullStateContract.FIXTURE_ABS_DIR} && " +
                "echo $B2_PNG_BASE64 | base64 -d > ${FullPartialFullStateContract.FIXTURE_ABS_DIR}/$name && " +
                "touch -t $touchTime ${FullPartialFullStateContract.FIXTURE_ABS_DIR}/$name && " +
                "ls -l ${FullPartialFullStateContract.FIXTURE_ABS_DIR}/$name 2>&1"
        )
        check(written.contains(name)) { "边界2夹具落盘失败（ls 无回显）：$name：$written" }
        ui.shellScript(
            "content call --uri content://media/ --method scan_file " +
                "--arg ${FullPartialFullStateContract.FIXTURE_ABS_DIR}/$name 2>&1"
        )
        if (!awaitShellRow(name, timeoutMs = 10000)) {
            // 宿主管线同款兜底：广播扫描后再核对
            ui.shellScript(
                "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE " +
                    "-d file://${FullPartialFullStateContract.FIXTURE_ABS_DIR}/$name 2>&1"
            )
            check(awaitShellRow(name, timeoutMs = 10000)) {
                "边界2夹具 scan 入库失败（scan_file + 广播兜底均未产生行）：$name：" +
                    FullPartialFullStateContract.shellRowQuery(name) { ui.shellScript(it) }
            }
        }
        FullPartialFullStateContract.assertShellRowExists(name) { command -> ui.shellScript(command) }
        Log.i(TAG, "b2 fixture injected: $name (touch=$touchTime, shell identity row confirmed)")
    }

    /** 截止轮询夹具行入库（scan 后 MediaProvider 异步索引）；超时返回 false（查询原文在日志）。 */
    private fun awaitShellRow(name: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val out = FullPartialFullStateContract.shellRowQuery(name) { ui.shellScript(it) }
            if ("Row: 0" in out && "_display_name=$name" in out) return true
            SystemClock.sleep(150)
        }
        return false
    }

    /**
     * 夹具行 _id（shell 面查询解析）——deck 批次日志 uri 的卷限定形式随 API/构造点
     * 不同（external / external_primary，CI logcat 实证为 external_primary），故池集
     * 相等断言按 **行 id** 比对（uri 末段，形式无关）。
     */
    private fun boundary2RowId(name: String): String {
        val out = ui.shellScript(
            "content query --uri content://media/external/images/media " +
                "--projection _id --where \"_display_name='$name'\" 2>&1"
        )
        return Regex("_id=(\\d+)").find(out)?.groupValues?.get(1)
            ?: error("边界2夹具行 _id 解析失败：$name：$out")
    }

    /**
     * 回前台等待（Home→am start 之后）：清理卡（g3，activity 未重建路径）或全局 deck
     * 状态文本（张待整理，activity 被系统重建→清理态丢失路径）任一回归 = 应用前台已
     * 渲染。两者分别对应修订重查后的真实界面形态，均接受。
     */
    private fun awaitForegroundAfterResume(tag: String) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = ui.ins.uiAutomation.rootInActiveWindow
            if (root != null && (
                ui.find(root, FullPartialFullStateContract.G3_NAME) != null ||
                    ui.find(root, ui.DECK_MARKER) != null
                )
            ) {
                Log.i(TAG, "b2 foreground rendered after resume ($tag)")
                return
            }
            SystemClock.sleep(150)
        }
        ui.dumpTree("timeout-b2-foreground-$tag")
        ui.saveScreenshot("timeout-b2-foreground-$tag")
        error("边界2：回前台后应用界面未渲染（$tag）")
    }

    /**
     * 边界 2 消费观察（截止轮询，设计"截止轮询断言"形态；两断言同一轮询环）：
     *
     * 每轮经真实 sheet 退出（"全局"段 = 生产 onSwitchToGlobal：保存断点[清理态首轮] +
     * 清空全局批次 + needsInitialBatch=true，DeckScreen.kt:1744-1764）触发全局重取批
     * → getRecommendedBatch(活投影)（DeckScreen.kt:537 LaunchedEffect）→ 生产批次日志
     * currentBatch<-[batch-site-3]（DeckScreen 自有 Log，全批 uri:dateModified）：
     * ①**池集相等断言（确定性投影证据）**：批次日志按行 id 与期望池集合相等——新成员
     *   入池 = ON_RESUME 重扫描已提交（应用无 ContentObserver，会话内注入唯一重扫触发
     *   即 ON_RESUME 修订重查链）；既有成员无丢失；g1/g2 回收站行不入池 = 修订重查后
     *   集合保持正确。批次 = 整池（池 6/7 ≤ batchSize 默认 15，RANDOM_WALK 池≤batchSize
     *   自清冷却，RecommendationEngine 实证）。
     * ②**deck 窗口 displayName 节点可见**（设计"n2 节点可见"）：RANDOM_WALK 洗牌决定
     *   卡位（当前+peek ≤3 槽，ImageCard contentDescription=displayName）——节点可见性
     *   依赖洗牌位置，每轮重取批 = 重新洗牌，轮询至命中；洗牌位置不确定性如实登记
     *   （coord/notes-B2.md §九），确定性投影证据由 ①承担。
     */
    private fun pollDeckConsumption(name: String, expectedPoolIds: Set<String>, tag: String) {
        val deadline = SystemClock.uptimeMillis() + B2_CONSUME_DEADLINE_MS
        var cycles = 0
        var poolConfirmed = false
        var nodeSeen = false
        var lastBatchLine = ""
        while (SystemClock.uptimeMillis() < deadline) {
            cycles++
            FullPartialFullUi.exitCleanupViaSheet()
            val dump = ui.shell("logcat -d -s DeckScreen")
            lastBatchLine = dump.lineSequence()
                .lastOrNull { it.contains(B2_BATCH_LOG_ANCHOR) } ?: ""
            val batchIds = lastBatchLine.substringAfter(B2_BATCH_LOG_ANCHOR, "")
                .split(",")
                .mapNotNull { entry ->
                    val id = entry.trim().substringBeforeLast(":").substringAfterLast("/", "")
                    if (id.matches(Regex("\\d+"))) id else null
                }
                .toSet()
            if (batchIds == expectedPoolIds) {
                if (!poolConfirmed) {
                    Log.i(TAG, "b2 pool confirmed after $cycles cycle(s) ($tag): ids=$batchIds")
                }
                poolConfirmed = true
            }
            // 轮内短窗节点轮询（卡与状态文本同帧渲染，容小延迟）
            val nodeDeadline = SystemClock.uptimeMillis() + 1500
            while (SystemClock.uptimeMillis() < nodeDeadline && !nodeSeen) {
                nodeSeen = ui.find(ui.ins.uiAutomation.rootInActiveWindow, name) != null
                if (!nodeSeen) SystemClock.sleep(150)
            }
            if (poolConfirmed && nodeSeen) {
                Log.i(TAG, "b2 $name consumed by deck after $cycles cycle(s): pool + node both confirmed ($tag)")
                return
            }
            SystemClock.sleep(200)
        }
        ui.dumpTree("timeout-b2-consume-$tag")
        ui.saveScreenshot("timeout-b2-consume-$tag")
        error(
            "边界2消费观察截止轮询超时（$cycles 轮，$tag）：name=$name " +
                "poolConfirmed=$poolConfirmed nodeSeen=$nodeSeen " +
                "lastBatchLine=$lastBatchLine expectedPoolIds=$expectedPoolIds"
        )
    }

    @Test
    fun fullRestoreKeepsProcessedMemberResumesCheckpointAndIgnoresLateSnapshot() = runBlocking<Unit> {
        val expected = FullPartialFullStateContract.readExpectedState(ctx)
        assertNotEquals(
            "Phase3 必须运行在 Phase2 之后的新进程（宿主 force-stop）",
            expected.getInt("phase2Pid"), android.os.Process.myPid()
        )
        val albumId = expected.getString("albumId")
        val groupId = expected.getString("groupId")
        val checkpointIndex = expected.getInt("checkpointIndex")
        val groupMembers = expected.getJSONArray("members").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val memberNames = expected.getJSONArray("memberDisplayNames").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        val sentinelName = expected.getString("sentinelName")
        val g1Key = expected.getJSONArray("processedMembers").getString(0)
        val g2Key = groupMembers[1]
        val g3Key = groupMembers[2]
        val preferences = AppPreferences.getInstance(ctx)

        // ---- 判据 8：地面真值（无重复无缺失 + 哨兵存在性）+ shell 双面核对 ----
        val visibleList = visibleDisplayNameList(ctx)
        val visibleSet = visibleList.toSet()
        assertTrue(
            "恢复后可见集合不得有重复行（list=${visibleList.size} set=${visibleSet.size}）",
            visibleList.size == visibleSet.size
        )
        assertEquals(
            "FULL 恢复地面真值：可见集合恰为 6 成员（无重复无缺失）；实际=$visibleSet",
            FullPartialFullStateContract.MEMBER_NAMES.toSet(), visibleSet - sentinelName
        )
        assertTrue("哨兵存在性核对", sentinelName in visibleSet)
        for (name in FullPartialFullStateContract.ALL_NAMES) {
            assertShellRowExists(name) { command -> ui.shellScript(command) }
        }

        // ---- 判据 9（记录读回，驱动 UI 前）：已处理保持、断点原样、无重复无丢失 ----
        assertEquals(
            "断点必须原样（真实 UI 自保存状态 (G,0)，PARTIAL 期间只读未触碰——边界 3 基准）",
            listOf(groupId) to checkpointIndex,
            preferences.getAlbumCleanupCheckpoint(albumId)
        )
        assertEquals(
            "g1 已处理记录必须保持", setOf(g1Key),
            preferences.getAlbumAcknowledgedCleanupMembers(albumId)
        )
        val trash = RecycleBinManager.getInstance(ctx)
        assertEquals(
            "回收站记录必须保持（g1）",
            listOf(g1Key), runBlocking { trash.loadRecycleBin().map { it.mediaIdentityKey() } }
        )
        assertEquals("组键必须原样（无重复无丢失）", groupMembers,
            preferences.getGroupImageIds(albumId, groupId))
        assertTrue(
            "组不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
        assertTrue(albumId !in preferences.getCompletedCleanupAlbumIds())

        // ---- 判据 9-边界3（生产续接契约）：UI 自保存断点的安全重启 ----
        // 生产入口 getCheckpointBatch 的放弃规则（AlbumCleanupEngine.kt:800-804）：
        // 断点保存组内任一成员已 acknowledged → clearCheckpoint + 返回 null（"明确
        // 删除已缩短可操作成员，旧索引无法判断保存与删除先后。安全从该原组剩余行
        // 重开，不能盲目平移而漏掉尚未浏览的成员"）。UI 自保存 ([G],0) 含已处理
        // g1 → 契约必须放弃并清除（盲目平移索引 0 将重新交付已处理的 g1）。预期值
        // 来自生产契约明文，非被测算法重算。
        val engine = FullRecoveryStateContract.cleanupEngine(ctx)
        // 输入 = 真实月份专辑行（与生产 deck 同口径：可见全集 7 行）
        val allVisibleRows = visibleList.map { rebuildRow(ctx, it) }
        val monthInfos = com.tabula.v3.ui.components.buildMonthInfos(allVisibleRows)
        assertEquals("全部夹具同月（生产月专辑唯一）", 1, monthInfos.size)
        val month = monthInfos.single()
        val resumed = engine.getCheckpointBatch(albumId, month.images, latestSnapshot = allVisibleRows)
        assertNull(
            "UI 自保存断点 ([G],0) 含 acknowledged 成员 g1——生产放弃规则必须返回 null（安全重启）",
            resumed
        )
        assertNull(
            "放弃规则必须已清除断点（仅清断点，不动组记录/已处理）",
            preferences.getAlbumCleanupCheckpoint(albumId)
        )
        // 放弃不得误伤进度记录：组键/已处理/回收站原样
        assertEquals("组键必须原样（放弃只清断点）", groupMembers,
            preferences.getGroupImageIds(albumId, groupId))
        assertEquals("g1 已处理记录必须保持", setOf(g1Key),
            preferences.getAlbumAcknowledgedCleanupMembers(albumId))
        // 安全重启：按组目录+acknowledged 重算 → 批次 = 剩余成员持久键序 [g2,g3]
        // （≥2 有序未处理成员——成员顺序可断言；g1 经 acknowledged 过滤不交付）
        val batch = requireNotNull(
            engine.getNextBatch(albumId, month.images, latestSnapshot = allVisibleRows)
        ) { "engine must resume with the pending members after safe restart" }
        assertEquals(
            "安全重启批次必须恰为 [g2,g3]（持久键序；g1 经 acknowledged 过滤不交付）",
            listOf(g2Key, g3Key), batch.images.map { it.mediaIdentityKey() }
        )
        assertEquals(listOf(groupId), batch.groupIds)
        assertTrue(
            "引擎取批后组仍不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )

        // ---- 判据 10：旧快照晚到不覆盖（真实生产链受控晚到，PR50 同形态）----
        // 旧扫描 = 滞留在 PARTIAL 时代投影 {b1,b2}（结果在手上、底层读取不可中断）；
        // 新扫描 = 默认真实生产 load（loadAllData + 回收站）；取消语义 = 键控重启。
        val recycleBinManager = RecycleBinManager.getInstance(ctx)
        val albumManager = AlbumManager(ctx)
        val fileOperationManager = FileOperationManager(ctx)
        val startedOld = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldReadReturned = AtomicBoolean(false)
        val committedBy = AtomicReference<String?>(null)
        val errors = AtomicReference<String?>(null)
        val appliedKeys = AtomicReference<List<String>>(emptyList())

        val bRows = FullPartialFullStateContract.B_NAMES.map { rebuildRow(ctx, it) }
        val stalePartialSnapshot = LocalImageRepository.LoadResult(
            allImages = bRows,
            imagesExcludingTabula = bRows,
            systemBuckets = emptyList()
        )
        val oldScan = launch {
            loadAndApplyMediaSnapshot(
                context = ctx,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { errors.set("old") },
                onFinally = {},
                load = {
                    startedOld.complete(Unit)
                    withContext(NonCancellable) { releaseOld.await() }
                    oldReadReturned.set(true)
                    Pair(stalePartialSnapshot, emptyList())
                },
                apply = { _, _ -> committedBy.set("old") }
            )
        }
        kotlinx.coroutines.withTimeout(10_000) { startedOld.await() }
        // 新权限状态生效：键控重启取消在途旧扫描（其底层读取暂不响应）
        oldScan.cancel()

        // 新扫描完成并提交（真实生产 load——恢复后的 FULL 全池）
        val newScan = launch {
            loadAndApplyMediaSnapshot(
                context = ctx,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { errors.set("new") },
                onFinally = {},
                apply = { projected, _ ->
                    committedBy.set("new")
                    appliedKeys.set(projected.allImages.map { it.mediaIdentityKey() }.sorted())
                }
            )
        }
        newScan.join()

        // 释放旧读取——它确实返回（晚到形态成立），但提交必须被取消检查拦截
        releaseOld.complete(Unit)
        oldScan.join()

        assertTrue("旧读取确实返回了（晚到形态成立）", oldReadReturned.get())
        assertEquals("提交只归新扫描", "new", committedBy.get())
        assertTrue("取消不得落 error 态", errors.get() == null)
        // 新扫描 apply 到的投影 = 当前真实全池投影：7 行 − 回收站 g1 = 6 行（真实数据）
        val expectedProjectedKeys = allVisibleRows.map { it.mediaIdentityKey() }
            .filter { it != g1Key }.toSet()
        assertEquals(
            "新扫描投影必须等于当前真实快照−软删除集合（旧快照内容不得混入）",
            expectedProjectedKeys, appliedKeys.get().toSet()
        )
        Log.i(TAG, "late-arrival race: old returned but never committed; new committed real projection")

        // ---- 判据 9'：UI 续接（生产安全重启路径）→ deck 当前卡 = g2 ----
        FullPartialFullUi.launchApp()
        ui.waitFor(ui.DECK_MARKER)
        FullPartialFullUi.startMonth(month.displayName)
        FullPartialFullUi.waitForExact(FullPartialFullStateContract.G2_NAME)
        FullPartialFullUi.awaitGone(
            FullPartialFullStateContract.G1_NAME, tag = "g1-not-reentered"
        )
        Log.i(TAG, "deck resumed at g2 via production safe-restart path")

        // ---- 边界 3：成员顺序 + 后续处理结果（真实 deck 上逐卡落实）----
        // 上滑处理续接批次首卡 g2 → 下一卡必为 g3（持久键序 [g2,g3] 在真实 deck 上
        // 成立——单成员批次无法区分"索引续接/重算/顺序"多种定位行为，≥2 有序成员
        // 即为此设计）→ 生产撤销窗口提交（acknowledged/回收站）
        FullPartialFullUi.removeCard(FullPartialFullStateContract.G2_NAME)
        FullPartialFullUi.waitForExact(FullPartialFullStateContract.G3_NAME)
        FullPartialFullUi.awaitDismissed(
            FullPartialFullStateContract.G2_NAME, tag = "g2-removed-from-deck"
        )
        FullPartialFullUi.await("g2-removal-committed") {
            runBlocking { trash.loadRecycleBin().map { it.mediaIdentityKey() } } ==
                listOf(g1Key, g2Key) &&
                preferences.getAlbumAcknowledgedCleanupMembers(albumId) ==
                    setOf(g1Key, g2Key)
        }
        Log.i(TAG, "resumed batch processed in persistent order: g2 committed, deck at g3")

        // ---- 判据 10'：最终一致性（最终 UI/查询状态与第 9 条一致）----
        val finalVisible = visibleDisplayNameList(ctx).toSet()
        assertEquals(
            "最终可见集合必须与恢复地面真值一致（晚到旧快照不得覆盖）",
            FullPartialFullStateContract.MEMBER_NAMES.toSet() + sentinelName, finalVisible
        )
        assertEquals(
            "最终已处理记录 = {g1,g2}（Phase3 UI 续接后真实提交）",
            setOf(g1Key, g2Key),
            preferences.getAlbumAcknowledgedCleanupMembers(albumId)
        )
        assertEquals(
            "最终回收站保持（追加序 [g1,g2]）",
            listOf(g1Key, g2Key),
            runBlocking { trash.loadRecycleBin().map { it.mediaIdentityKey() } }
        )
        assertEquals("最终组键保持（无重复无丢失）", groupMembers,
            preferences.getGroupImageIds(albumId, groupId))
        assertTrue(
            "最终仍不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
        // 月份归属核对（FullRecoveryStateContract 形态字段；生产分组错误即红灯）
        val finalMonthInfos = com.tabula.v3.ui.components.buildMonthInfos(
            memberNames.map { rebuildRow(ctx, it) }
        )
        val monthAttribution = expected.getJSONArray("monthAttribution")
        for (i in 0 until monthAttribution.length()) {
            val entry = monthAttribution.getJSONObject(i)
            val identityKey = entry.getString("identityKey")
            val containingMonth = finalMonthInfos.firstOrNull { mi ->
                mi.images.any { it.mediaIdentityKey() == identityKey }
            }
            assertTrue("成员 $identityKey 必须仍在月列表中（无丢失）", containingMonth != null)
            assertEquals(
                "成员 $identityKey 月份归属必须一致",
                entry.getInt("month"), containingMonth!!.month
            )
            assertEquals(
                "成员 $identityKey 年份必须一致",
                entry.getInt("year"), containingMonth.year
            )
        }
        // ================= 边界 2：会话内媒体插入 + Resume 触发修订重扫（真实接线验证） =================
        // 目标（t13-overlay-acceptance.md §边界 2，2026-11-14 协调者定稿）：真实 MainActivity
        // 接线——会话内 shell 注入新媒体 → ON_RESUME 权限修订重查 + 重扫描 → 新成员进入
        // 应用消费面（deck/图库窗口）且集合保持正确。生产链（读码实证）：onResume →
        // checkPermission(foregroundRefresh=true)（MainActivity.kt:342-352）→
        // applyAccessLevel 级别不变+前台刷新同样 mediaAccessRevision++（:405）→
        // LaunchedEffect(revision,...) 键控重启（取消在途旧扫描）→ loadAndApplyMediaSnapshot
        // 提交 allImages（:576-620）。应用无 ContentObserver（全库 grep 零命中）：会话内
        // 注入在生产侧唯一重扫触发即 ON_RESUME——与设计"ContentObserver/Resume"中实际
        // 存在的通道一致（如实登记）。退出清理（真实 sheet）为 deck 侧消费触发：
        // onSwitchToGlobal 清空全局批次 + needsInitialBatch=true → getRecommendedBatch
        // 重取批（消费活投影）。注入序 = 设计两循环（n2 → n3，各自 mtime 全套最晚）。
        // 如实登记（设计允许形态）：旧读取"晚返回"的确定性构造设备层不可行（生产 load
        // 无注入点）——该语义由单测级 MediaSnapshotLoadRaceTest（PR50）覆盖；本段验证
        // 最终状态正确性（真实接线的信息流终态）。
        Log.i(TAG, "b2: session media injection + resume revision rescan (real wiring) begin")

        // ---- B2 循环 1：注入 n2（shell 身份，mtime 全套最晚）→ 行入库 shell 面核对 ----
        injectBoundary2Fixture(B2_N2_NAME, B2_N2_TOUCH_TIME)
        val b2SurvivorIds = listOf(
            FullPartialFullStateContract.G3_NAME,
            FullPartialFullStateContract.N1_NAME,
            FullPartialFullStateContract.B1_NAME,
            FullPartialFullStateContract.B2_NAME,
            FullPartialFullStateContract.SENTINEL_NAME
        ).map { boundary2RowId(it) }.toSet()
        val n2RowId = boundary2RowId(B2_N2_NAME)
        // 地面真值：注入后 MediaStore 行 = 7 夹具 + n2（无重复；g1/g2 行仍在库——回收站
        // 是应用投影语义，MediaStore 行不删，判据 8 原语同口径）
        val visibleAfterN2 = visibleDisplayNameList(ctx)
        assertEquals(
            "注入 n2 后 MediaStore 行恰为 7 夹具+n2（无缺失无多余）",
            (FullPartialFullStateContract.MEMBER_NAMES + FullPartialFullStateContract.SENTINEL_NAME + B2_N2_NAME).toSet(),
            visibleAfterN2.toSet()
        )
        assertEquals(
            "注入 n2 后 MediaStore 行无重复",
            visibleAfterN2.size, visibleAfterN2.toSet().size
        )

        // ---- 触发应用重扫：Home（app pause，后台在途扫描继续）→ 2s → am start 回前台 ----
        // （ON_RESUME → 权限修订重查：级别不变+前台刷新同样递增 revision → 重扫描）
        ui.shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(2000)
        FullPartialFullUi.launchApp()
        awaitForegroundAfterResume("n2-resume")

        // ---- 消费观察（截止轮询）：批次日志池集相等（n2 入池 + 既有无丢失 + 回收站行
        // 不入池）+ deck 窗口 n2 节点可见（洗牌位重试轮，确定性证据由批次日志承担）----
        pollDeckConsumption(
            B2_N2_NAME,
            expectedPoolIds = b2SurvivorIds + n2RowId,
            tag = "n2-loop"
        )

        // ---- B2 循环 2：注入 n3（mtime 晚于 n2）→ Home → Resume → 同链路 ----
        injectBoundary2Fixture(B2_N3_NAME, B2_N3_TOUCH_TIME)
        val n3RowId = boundary2RowId(B2_N3_NAME)
        // 全集无重复：MediaStore 行 = 7 夹具 + n2 + n3（n2 跨循环保持，无重复无缺失）
        val visibleAfterN3 = visibleDisplayNameList(ctx)
        assertEquals(
            "注入 n3 后 MediaStore 行恰为 7 夹具+n2+n3（全集无缺失无多余）",
            (FullPartialFullStateContract.MEMBER_NAMES + FullPartialFullStateContract.SENTINEL_NAME +
                B2_N2_NAME + B2_N3_NAME).toSet(),
            visibleAfterN3.toSet()
        )
        assertEquals(
            "注入 n3 后 MediaStore 行无重复（全集无重复）",
            visibleAfterN3.size, visibleAfterN3.toSet().size
        )
        ui.shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(2000)
        FullPartialFullUi.launchApp()
        awaitForegroundAfterResume("n3-resume")
        pollDeckConsumption(
            B2_N3_NAME,
            expectedPoolIds = b2SurvivorIds + n2RowId + n3RowId,
            tag = "n3-loop"
        )

        // ---- 哨兵 s1 shell 面存活（两循环修订重扫后）----
        FullPartialFullStateContract.assertShellRowExists(FullPartialFullStateContract.SENTINEL_NAME) { command ->
            ui.shellScript(command)
        }
        Log.i(TAG, "b2: both loops consumed, sentinel s1 alive (shell side)")

        // ---- 用例末：n2/n3 行+文件清理（防残留污染后续 probe 段；@After 另有兜底）----
        for (name in listOf(B2_N2_NAME, B2_N3_NAME)) {
            val gone = shellDeleteFixture(name) { command -> ui.shellScript(command) }
            Log.i(TAG, "b2 cleanup $name gone=$gone")
            assertTrue("边界2夹具用例末清理后必须消失：$name", gone)
        }
        ui.shellScript(
            "rm -f ${FullPartialFullStateContract.FIXTURE_ABS_DIR}/$B2_N2_NAME " +
                "${FullPartialFullStateContract.FIXTURE_ABS_DIR}/$B2_N3_NAME 2>&1"
        )
        Log.i(TAG, "phase FULL-recovery complete: safe-restart resumed [g2,g3], g2 committed, race guarded, b2 wiring verified")
    }
}
