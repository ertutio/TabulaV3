package com.tabula.v3

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.SwipeStyle
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.mediaIdentityKey
import com.tabula.v3.FullPartialFullStateContract.visibleDisplayNameList
import com.tabula.v3.FullPartialFullStateContract.visibleDisplayNameListOrNull
import com.tabula.v3.FullRecoveryStateContract.flushCleanupPrefs
import com.tabula.v3.FullRecoveryStateContract.seedCleanupGroup
import com.tabula.v3.permission.MediaPermissionPolicy
import com.tabula.v3.ui.components.buildMonthInfos
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 旧扫描晚返回设备验收（2026-11-15，Unit B）：真实 MainActivity 权限变化 →
 * mediaAccessRevision → 加载 effect → 生产快照提交 → 界面消费 链条上，**旧扫描
 * 晚返回不得覆盖新集合、取消不得被误呈现为加载失败**。经 [MediaLoadTestHook]
 * 显式握手建立五步顺序（单方法，预期 case 数 = **1**，probe 段
 * "StaleScanLateReturn" OK(1) 精确门），全程同进程（pidof 恒等）。
 *
 * 覆盖层级登记（如实口径，与执行指令 §三 一致）：
 * - **本类 = 真实接线级**：竞态构造点在**生产链内**（loadAndApplyMediaSnapshot
 *   的 withContext(Dispatchers.IO) 块内 debug 接缝），旧扫描 = 真实生产扫描
 *   （真实 loadAllData + projectSnapshot + albumManager.initialize + 真实
 *   LaunchedEffect 键控取消 + 真实 apply 闭包），非注入替身；
 * - **PR50 MediaSnapshotLoadRaceTest = 单测级**（Robolectric + 参数注入 load/apply，
 *   自成体系）——两级口径各自保留，不互相冒充；
 * - 严格竞态由接缝构造：非接缝的自然时序（B2MediaAccessRevisionDeviceTest）只能
 *   验证信息流终态，无法确定性地让"旧读取晚返回"撞上"新提交已完成"——本类用
 *   debug 接缝把该撞点**确定性化**（park → 新提交完成 → release），这是与
 *   非接缝测试的本质区别；接缝仅 DEBUG 生效，release 构建零行为变化。
 *
 * 握手五步（每步断言 + logcat 日志锚）：
 *  1. **旧扫描进入读取并停在可控位**：真实系统机制建立 PARTIAL(B)（系统权限
 *     对话框 Select photos → 照片选择器 7 格像素驱动只选 {1,2}=b2,b1 + 哨兵 s1；
 *     FPF Phase2/B2MR 步骤1 同款原语）→ deck 就绪（初始加载已过接缝，门未武装）
 *     → MediaLoadTestHook.armOnce()（测试同进程直调）→ Home→am start
 *     （onResume → applyAccessLevel 级别不变+foregroundRefresh → revision++）→
 *     轮询断言 parkedRows()==2（=PARTIAL 选中集合规模：暂停者 = 旧选择集合时代
 *     扫描，且其读取已返回、投影在手上）。
 *  2. **真实权限变化 + 前台检查触发新 revision/扫描**：会话内 shell 注入 40
 *     填充媒体（B2MR 步骤2 同款口径：MediaStore=47 行、应用可见仍={b1,b2}，
 *     无 ContentObserver 注入不动界面）→ shell pm grant READ_MEDIA_IMAGES
 *     （grant 不杀进程 #52 口径）→ Home→am start（onResume 级别变化分支
 *     revision++ → 键控重启：被暂停的旧扫描被取消，取消被 NonCancellable
 *     推迟到释放后）。
 *  3. **新扫描提交，界面+消费池呈现新集合**：集合面截止轮询 = 47 名全集
 *     （含哨兵 s1 存在性、无重复）+ deck 存活行同步观察窗（无门控/白屏，
 *     B2MR 步骤5b 同款）+ 消费池边界断言：真实 Settings 推荐模式现值重选
 *     （B2MR 步骤5c 同款）→ STALE_BOUNDARY_MARKER 在 tap 前写入 → 边界后
 *     batch-site-4 批次日志行 id 集 == 47 池集（边界后无新事件/仅动作前日志
 *     = 红灯，fail-closed）。
 *  4. **释放旧扫描，确认旧读取确实返回**：BACK 回 deck（SETTINGS→DECK 生产回退；
 *     释放与观察窗在 deck 前景执行——Settings 前景下 deck 为 backgroundContent，
 *     标记不在活动窗树内）→ MediaLoadTestHook.release() → 轮询断言 isReleased()
 *     且 logcat（MediaLoadTestHook tag）出现 "resumed rows=2"——旧读取结果确实
 *     浮出（rows=2 即旧选择集合投影），提交与否归生产链恢复期取消检查。
 *  5. **旧集合不覆盖 + 取消不误报失败**：≥5s 观察窗（deck 前景）：deck 标记在窗；
 *     无门控（hasPermission 保持）；无"回收站记录未能读取"对话框；MediaStore
 *     可见面 47（可见性不变量——旧投影 apply 只改应用内存 allImages，不动
 *     MediaStore）。**覆盖检测器（确定性重取批）**：第二次真实 Settings 推荐
 *     模式现值重选触发 deck 从当前已提交池重取——释放标记后全部 DeckScreen
 *     批次行 id 集 == 47 池集且至少一条存在（若旧扫描释放后曾提交 2 行覆盖，
 *     重取批/同步批次出现 2 行事件即红灯；池 ≤ batchSize 50 → 整池重取确定
 *     性成立）；logcat 无 TabulaApp "Could not load/reconcile recycle bin"
 *     错误行（CancellationException 原样重抛不落 onError——PR50 语义的设备面）。
 *     **清理状态面**：暂停前已经真实生产写入口建立 组{g1,g2}+断点+已处理
 *     （FPF Phase1 建组模式复制，见下方"建组模式登记"），步骤5 以新
 *     AlbumManager 实例 + AppPreferences 读回断言 组键/断点/已处理 原样
 *     （FPF Phase3 Continuation 读回模式）——旧扫描在暂停前已执行过
 *     albumManager.initialize(旧投影)，此断言验证 stale initialize 无持久污染。
 *
 * 建组模式登记（解读口径，与指令"FPF Phase1 建组模式复制：读该测试文件提取
 * 最小调用序列"的取舍如实说明）：
 * - 组状态经**真实生产写入口**建立（与 FPF Phase1 的建组入口同源）：
 *   FullRecoveryStateContract.seedCleanupGroup（saveAlbumAnalysisResult 分析 +
 *   saveGroupsImageIds 组键，g1,g2 持久键序）、AppPreferences
 *   .acknowledgeCleanupMember（已处理 g1 记录）、AppPreferences
 *   .saveAlbumCleanupCheckpoint（断点 ([G],0)——AlbumCleanupEngine:707 生产
 *   saveCheckpoint 同款入口）、flushCleanupPrefs 同步落盘；
 * - FPF Phase1 的"真实月份清理 UI 上滑处理 g1"腿在本类**不可行**：该腿要求
 *   g1/g2 对应用可见（清理批按可见行交付），而本类会话内权限为 PARTIAL(B)
 *   （可见恰={b1,b2}）；PARTIAL→NONE 需 pm revoke（强停应用进程**含
 *   instrumentation**，B2PR/FPF #52 口径），与单 instrumentation 单例（probe
 *   依赖门不适用）冲突——故已处理/断点经生产写入口直接落盘，如实登记；
 * - Phase1 提交腿的**回收站记录不种子**：回收站条目会使生产投影减去软删除行
 *   （projectSnapshot 契约，FPF Phase3 判据 10 实证 7−1=6），步骤3 的 47 池
 *   算术将变 46——污染断言面（组键/断点/已处理三元组）不依赖回收站腿，
 *   如实登记；"已处理记录而无回收站条目"非生产可达组合，属状态面种子工件，
 *   其用途（initialize 写路径无持久污染检测）不受影响。
 *
 * 行身份与夹具纪律（B2MR 同款）：全部 shell 身份（base64 **文本**通道 on-device
 * 落盘 + scan_file 入库 + 广播兜底 + 行截止轮询 + shell 行核对，
 * FullPartialFullStateContract.ensureG3Fixture 同款——字节不经 UiAutomation）。
 * 7 张命名夹具 Pictures/StaleScanLateReturnFixtures/（mtime 序 =
 * s1<n1<g1<g2<b1<b2<g3 且 g3 最后入库——"时间倒序"与"_id 倒序"两模型同网格序
 * [G3,B2,B1,G2,G1,N1,S1]，B={1,2} 不依赖未实证排序模型）；填充 40 张同目录
 * stale-fill-01..40（mtime 晚于全部命名夹具）。用例末自清（目录整体删除 +
 * 逐项核对）+ @After 幂等兜底。留证文件名无 `|`；截止轮询无固定 sleep
 * （等待均为有截止时间的轮询；唯一固定 sleep = Home→am start 的过渡等待，
 * B2MR 同款）。不动 app/src/main（接缝 2 处除外）、不动 app/src/test。
 */
@RunWith(AndroidJUnit4::class)
class StaleScanLateReturnDeviceTest {
    private val TAG = "StaleScanLateReturn"
    private val ui get() = PartialSelectionUi
    private val ctx get() = ui.ctx

    // ==================== 夹具契约（shell 身份，Pictures/StaleScanLateReturnFixtures/） ====================

    private val FIXTURE_DIR = "Pictures/StaleScanLateReturnFixtures/"
    private val FIXTURE_ABS_DIR = "/sdcard/Pictures/StaleScanLateReturnFixtures"

    /** 命名夹具 7 张（语义名沿用 FPF 模型：哨兵 s1 不入集合断言集，仅存在性）。 */
    private val SENTINEL_NAME = "stale-s1.png"
    private val N1_NAME = "stale-n1.png"
    private val G1_NAME = "stale-g1.png"
    private val G2_NAME = "stale-g2.png"
    private val G3_NAME = "stale-g3.png"
    private val B1_NAME = "stale-b1.png"
    private val B2_NAME = "stale-b2.png"

    /** B 集合（PARTIAL 选中集）与命名全集（哨兵除外）。 */
    private val B_NAMES = listOf(B1_NAME, B2_NAME)
    private val MEMBER_NAMES = listOf(G1_NAME, G2_NAME, G3_NAME, N1_NAME, B1_NAME, B2_NAME)
    private val NAMED_NAMES = listOf(SENTINEL_NAME) + MEMBER_NAMES

    /**
     * mtime（touch -t）序 = s1(11-01)→n1(11-03)→g1(11-05)→g2(11-06 12:00)→
     * b1(11-07)→b2(11-08 20:00)→g3(11-09 09:00 最晚)；种子写入序同序 → g3 行
     * _id 最大 → 两排序模型同网格序 [G3,B2,B1,G2,G1,N1,S1] → B = 网格索引
     * {1,2}（B2MR/FPF StateContract 类头同款设计）。
     */
    private val NAMED_TOUCH_TIMES = linkedMapOf(
        SENTINEL_NAME to "202611010900",
        N1_NAME to "202611030900",
        G1_NAME to "202611050900",
        G2_NAME to "202611061200",
        B1_NAME to "202611070900",
        B2_NAME to "202611082000",
        G3_NAME to "202611090900"
    )

    /** 清理组成员（持久键序 [g1,g2]；g1 = 已处理记录）。 */
    private val GROUP_MEMBER_NAMES = listOf(G1_NAME, G2_NAME)

    /**
     * 选择器网格位置 → 瓷片颜色占位（勾选态判定不依赖瓷片颜色——角标白色笔画
     * 为判据，PartialSelectionUi 实证口径；占位仅作数量核对输入，B2MR 同款）。
     */
    private val GRID_TILE_COLORS = listOf(0xFF00FF00, 0xFF00FFFF, 0xFFFFFF00, 0xFFFF00FF, 0xFFFF0000, 0xFF0000FF, 0xFF888888).map { it.toInt() }

    /** 填充媒体 40 张（池 47 = 7+40 > DEFAULT_BATCH_SIZE 15；batchSize 预置 50 → 重取批 = 整池）。 */
    private val FILLER_COUNT = 40
    private fun fillerName(index: Int): String = "stale-fill-%02d.png".format(index)
    private val FILLER_NAMES = (1..FILLER_COUNT).map { fillerName(it) }

    /** 填充 mtime：2026-11-10 09:01..09:40——均晚于 g3，同月不漂移（B2MR 同款）。 */
    private fun fillerTouchTime(index: Int): String = "2026111009%02d".format(index)

    /** FULL 地面真值全集 = 47 名（7 命名 + 40 填充），无重复无缺失。 */
    private val FULL_EXPECTED_NAMES: Set<String> = (NAMED_NAMES + FILLER_NAMES).toSet()

    /** 96x96 纯绿 PNG 的 base64（FullPartialFullStateContract.G3_PNG_BASE64 同款同源）。 */
    private val PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAIAAABt+uBvAAABC0lEQVR4nO3OMQ0AIAAEsfdvGhyw9gaSCuh29r34QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UHbBY393g69gixzAAAAAElFTkSuQmCC"

    /** 权限门控屏按钮文本（PermissionRequestScreen，MainActivity.kt）。 */
    private val GATE_BUTTON = "授权访问照片"

    /** 回收站读取失败对话框标题（onError 呈现面——取消不得落此态的界面面证据）。 */
    private val RECYCLE_ERROR_DIALOG = "回收站记录未能读取"

    /** 生产批次重取日志锚（DeckScreen.logBatchRows；batch-site-4 = 推荐模式刷新路径）。 */
    private val BATCH_LOG_ANCHOR = "currentBatch<-[batch-site-4]:"

    /** 生产加载-提交失败日志行（loadAndApplyMediaSnapshot onError 路径，TabulaApp tag）。 */
    private val LOAD_ERROR_LOG = "Could not load/reconcile recycle bin"

    /**
     * 消费面动作边界标记：批次断言只认该标记**之后**的 DeckScreen 批次事件
     * （B2MR REFETCH_BOUNDARY_MARKER 同款纪律：缓冲单进程 append 序单调；
     * 不用墙钟时间戳；不用 pid 过滤；标记被环淘汰 → fail-closed）。
     */
    private val STALE_BOUNDARY_MARKER = "stale-refetch-boundary:"

    /**
     * 释放动作边界标记：步骤5 的"取消不落 error 态 + 释放后无 2 行批次事件"
     * 只核对该标记**之后**的缓冲行（排除其他 probe 段/历史遗留行——logcat 缓冲
     * 跨 pm clear/跨段持续）。被环淘汰 → fail-closed。
     */
    private val STALE_RELEASE_MARKER = "stale-release-boundary:"

    /** batchSize 预置（coerceIn(5,50) 合法上界）：池 47 ≤ 50 → 重取批 = 整池。 */
    private val BATCH_SIZE_PREF = 50

    @Before fun setup() {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        check(Build.VERSION.SDK_INT >= 34) { "PARTIAL selection semantics require API 34+" }
    }

    // ==================== 共享原语（B2MR 同款 + 本类自建常量，勿跨类引用） ====================

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
        error("no node visible for ${labels.size} candidates ($tag)")
    }

    /**
     * shell 面 bulk 查询：store 全部图片行 (display_name → _id) 映射
     * （B2MR 同款；列分隔 = ", "（content query 实证格式））。
     */
    private fun shellStoreRows(): Map<String, String> =
        ui.shellScript(
            "content query --uri content://media/external/images/media " +
                "--projection _display_name:_id 2>&1"
        ).lineSequence()
            .filter { it.startsWith("Row:") }
            .mapNotNull { row ->
                val name = Regex("_display_name=([^,\r\n]+)").find(row)?.groupValues?.get(1)?.trim()
                    ?: return@mapNotNull null
                val id = Regex("_id=(\\d+)").find(row)?.groupValues?.get(1) ?: return@mapNotNull null
                name to id
            }
            .toMap()

    /** 目录级预清（测试首种子前一次；行 + 文件双面，幂等）。 */
    private fun cleanFixtureDir() {
        ui.shellScript(
            "content delete --uri content://media/external/images/media " +
                "--where \"relative_path='${FIXTURE_DIR.trimEnd('/')}'\" 2>&1"
        )
        ui.shellScript("rm -rf $FIXTURE_ABS_DIR 2>&1")
    }

    /**
     * 夹具批量注入（shell 身份，模板复制通道；B2MR injectFixturesBulk 同款）：
     * 按名幂等预清 → 模板 base64 解码 + for 循环 cp 落盘 → 逐文件 touch -t →
     * for 循环 scan_file 入库 → bulk 行核对，缺行走广播兜底再轮询 → 目录文件
     * 计数核对。行所有权 = shell（与宿主 adb push 同身份，PARTIAL 选择集语义
     * 可观测前提）。
     */
    private fun injectFixturesBulk(
        names: List<String>,
        touchTimeOf: (String) -> String,
        expectedDirTotal: Int
    ) {
        ui.shellScript(
            buildString {
                append("for n in ")
                names.forEach { append("$it ") }
                append("; do content delete --uri content://media/external/images/media ")
                append("--where \"_display_name=\$n AND relative_path='${FIXTURE_DIR.trimEnd('/')}'\"; ")
                append("done 2>&1")
            }
        )
        val writeScript = buildString {
            append("mkdir -p $FIXTURE_ABS_DIR\n")
            append("echo $PNG_BASE64 | base64 -d > $FIXTURE_ABS_DIR/.tpl.png\n")
            append("for n in ")
            names.forEach { append("$it ") }
            append("; do cp $FIXTURE_ABS_DIR/.tpl.png $FIXTURE_ABS_DIR/\$n; done\n")
            for (name in names) {
                append("touch -t ${touchTimeOf(name)} $FIXTURE_ABS_DIR/$name\n")
            }
            append("rm -f $FIXTURE_ABS_DIR/.tpl.png\n")
            append("ls -1 $FIXTURE_ABS_DIR | wc -l\n")
        }
        val written = ui.shellScript(writeScript).trim().lineSequence().lastOrNull()?.trim() ?: ""
        assertEquals(
            "夹具目录文件计数不符（ls|wc -l，累计值）：expect=$expectedDirTotal got='$written'",
            expectedDirTotal.toString(), written
        )
        ui.shellScript(
            "for f in $FIXTURE_ABS_DIR/*.png; do " +
                "content call --uri content://media/ --method scan_file --arg \$f; done 2>&1"
        )
        val deadline = SystemClock.uptimeMillis() + 20000
        var rows = shellStoreRows()
        while (SystemClock.uptimeMillis() < deadline && !names.all { it in rows }) {
            SystemClock.sleep(300)
            rows = shellStoreRows()
        }
        val missing = names.filter { it !in rows }
        if (missing.isNotEmpty()) {
            for (name in missing) {
                ui.shellScript(
                    "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE " +
                        "-d file://$FIXTURE_ABS_DIR/$name 2>&1"
                )
            }
            val retryDeadline = SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < retryDeadline) {
                rows = shellStoreRows()
                if (names.all { it in rows }) break
                SystemClock.sleep(300)
            }
        }
        val stillMissing = names.filter { it !in rows }
        check(stillMissing.isEmpty()) {
            "夹具 scan 入库失败（scan_file 批量 + 广播兜底均未产生行）：missing=$stillMissing"
        }
        Log.i(TAG, "fixtures injected: ${names.size} rows (shell identity, bulk channel)")
    }

    /** 目录整体删除 + 逐项核对行消失（幂等；返回仍残留行名单）。 */
    private fun cleanupAllFixtures(): List<String> {
        ui.shellScript(
            "content delete --uri content://media/external/images/media " +
                "--where \"relative_path='${FIXTURE_DIR.trimEnd('/')}'\" 2>&1"
        )
        ui.shellScript("rm -rf $FIXTURE_ABS_DIR 2>&1")
        val rows = shellStoreRows()
        return rows.keys.filter { it.startsWith("stale-") }
    }

    /**
     * 应用可见集合（应用权限身份 MediaStore 查询）截止轮询到期望集合（无重复）。
     * cursor=null 按语义登记继续（FPF 边界 1 同口径）；超时留证如实失败。
     */
    private fun awaitCollectionFace(expected: Set<String>, timeoutMs: Long, tag: String) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var actual: List<String>? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val visible = visibleDisplayNameListOrNull(ctx)
            if (visible != null && visible.toSet() == expected && visible.size == expected.size) {
                Log.i(TAG, "$tag collection face confirmed: ${visible.size} rows, no dups")
                return
            }
            actual = visible
            SystemClock.sleep(200)
        }
        ui.dumpTree("timeout-collection-$tag")
        ui.saveScreenshot("timeout-collection-$tag")
        error(
            "集合面未收敛到期望（$tag）：expected=${expected.size} 行，" +
                "actual=${actual?.size ?: "null-cursor"} 行 actualSet=${actual?.toSet()}"
        )
    }

    /**
     * 指定标记之后缓冲行的多 tag dump（保缓冲全局 append 序）：
     * `logcat -d -s` 多 tag 过滤不改缓冲行序——跨 tag 的"标记之后"判定成立
     * （B2MR 边界标记同款纪律）。标记被环淘汰 → 返回 null（调用方 fail-closed）。
     */
    private fun bufferLinesAfterMarker(marker: String): List<String>? =
        ui.shell("logcat -d -s $TAG DeckScreen TabulaApp MediaLoadTestHook").lineSequence().toList()
            .let { lines ->
                val markerIdx = lines.indexOfLast { it.contains(marker) }
                if (markerIdx < 0) null else lines.subList(markerIdx + 1, lines.size)
            }

    /** 批次日志行 → 行 id 集合（uri 末段，形式无关；B2MR/Phase3 同款解析）。 */
    private fun parseBatchIds(line: String): Set<String> =
        line.substringAfter(BATCH_LOG_ANCHOR, "")
            .split(",")
            .mapNotNull { entry ->
                val id = entry.trim().substringBeforeLast(":").substringAfterLast("/", "")
                if (id.matches(Regex("\\d+"))) id else null
            }
            .toSet()

    /**
     * deck 顶栏"设置"入口 → Settings 前景（B2MR openSettingsFromDeck 同款：
     * "设置"文本多节点命中歧义 → 逐候选点击（clickNode 祖先回退）+ 到达核验，
     * 至多 4 候选；全部失败留证报错）。
     */
    private fun openSettingsFromDeck() {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = ui.ins.uiAutomation.rootInActiveWindow
            if (ui.find(root, "推荐模式") != null || ui.find(root, "显示设置") != null) {
                Log.i(TAG, "settings screen reached")
                return
            }
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            ui.findAll(root, "设置", candidates)
            for (node in candidates) {
                ui.clickNode(node, "stale-open-settings")
                SystemClock.sleep(800)
                val after = ui.ins.uiAutomation.rootInActiveWindow
                if (ui.find(after, "推荐模式") != null || ui.find(after, "显示设置") != null) {
                    Log.i(TAG, "settings screen reached (candidate clicked)")
                    return
                }
            }
            SystemClock.sleep(300)
        }
        ui.dumpTree("timeout-stale-settings-entry")
        ui.saveScreenshot("timeout-stale-settings-entry")
        error("Settings 前景未到达（顶栏入口点击后推荐模式/显示设置行均不可见）")
    }

    /** 截止轮询条件（失败留证后 false）。 */
    private fun awaitDeadline(label: String, timeoutMs: Long = 20000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(150)
        }
        ui.dumpTree("deadline-$label")
        ui.saveScreenshot("deadline-$label")
        return false
    }

    /**
     * 清理组成员行（shell 面查询重建 ImageFile——PARTIAL(B) 下 g1/g2 对应用
     * 不可见，rebuildRow 的应用身份查询不可用；shell 身份字段与生产加载同源
     * （同卷同 _id），mediaIdentityKey 一致。date_modified 为秒 → *1000 对齐
     * rebuildRow 口径）。
     */
    private fun shellGroupRow(name: String): ImageFile {
        val out = ui.shellScript(
            "content query --uri content://media/external/images/media " +
                "--projection _id:_display_name:date_modified:volume_name:relative_path " +
                "--where \"_display_name='$name'\" 2>&1"
        )
        val row = out.lineSequence().firstOrNull { it.startsWith("Row:") }
            ?: error("组员行查询失败（无 Row 行）：$name：$out")
        val id = Regex("_id=(\\d+)").find(row)?.groupValues?.get(1)?.toLongOrNull()
            ?: error("组员行 _id 解析失败：$name：$row")
        val dm = Regex("date_modified=(\\d+)").find(row)?.groupValues?.get(1)?.toLongOrNull()
            ?: error("组员行 date_modified 解析失败：$name：$row")
        val volume = Regex("volume_name=([^,\r\n]+)").find(row)?.groupValues?.get(1)?.trim()
            ?: "external_primary"
        val relativePath = Regex("relative_path=([^,\r\n]+)").find(row)?.groupValues?.get(1)?.trim()
            ?: FIXTURE_DIR
        return ImageFile(
            id = id,
            uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), id),
            displayName = name,
            dateModified = dm * 1000,
            size = 1L,
            width = 96,
            height = 96,
            bucketDisplayName = "StaleScanLateReturnFixtures",
            volumeName = volume,
            relativePath = relativePath
        )
    }

    // ==================== 测试主体（单方法，probe OK(1) 精确门） ====================

    @Test
    fun parkedStaleScanReleasedAfterNewCommitNeverOverwritesAndNeverReportsFailure() {
        // ---- 前置：无任何照片权限（级别 NONE；probe 段 pm clear + 兜底 revoke 后裸状态）----
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
        // 引导态种子 + batchSize 预置 50（池 47 ≤ 50 → 重取批 = 整池，类头设计说明）
        AppPreferences(ctx).apply {
            hasCompletedOnboarding = true
            hasAcknowledgedDisclaimer = true
            swipeStyle = SwipeStyle.SHUFFLE
            batchSize = BATCH_SIZE_PREF
        }
        val preferences = AppPreferences.getInstance(ctx)
        preferences.clearAllAlbumCleanupState()

        // ---- 种子：7 命名夹具（shell 身份批量通道）+ store 恰 7 行地面真值 ----
        cleanFixtureDir()
        injectFixturesBulk(
            NAMED_NAMES,
            touchTimeOf = { name -> NAMED_TOUCH_TIMES.getValue(name) },
            expectedDirTotal = NAMED_NAMES.size
        )
        val storeAfterSeed = shellStoreRows()
        assertEquals(
            "种子后 store 必须恰为 7 命名夹具（无多余无缺失）；实际=${storeAfterSeed.keys}",
            NAMED_NAMES.toSet(), storeAfterSeed.keys
        )

        // ---- 清理状态面建立（FPF Phase1 建组模式的生产写入口最小序列，类头登记）----
        val groupRows = GROUP_MEMBER_NAMES.map { shellGroupRow(it) }
        assertEquals(GROUP_MEMBER_NAMES, groupRows.map { it.displayName })
        val groupKeys = groupRows.map { it.mediaIdentityKey() }
        val albumId = buildMonthInfos(groupRows).single().cleanupAlbumId
        val groupId = seedCleanupGroup(ctx, groupRows)
        assertEquals("组目录必须恰含 G 组", setOf(groupId), preferences.getAlbumGroupIds(albumId))
        assertEquals("组成员键必须为持久键序 [g1,g2]", groupKeys,
            preferences.getGroupImageIds(albumId, groupId))
        val g1Key = groupKeys[0]
        // 部分处理记录（生产写入口；回收站腿不种子——47 池算术，类头登记）
        preferences.acknowledgeCleanupMember(g1Key)
        // 断点保存（生产写入口，AlbumCleanupEngine saveCheckpoint 同款）：([G],0)
        preferences.saveAlbumCleanupCheckpoint(albumId, listOf(groupId), 0)
        assertTrue(
            "flush sentinel commit() 必须成功（同步落盘完成信号）",
            flushCleanupPrefs(ctx)
        )
        // 建立面地面真值（步骤5 读回的基准）
        assertEquals(
            "断点必须为 ([G],0)（组+当前帧索引；Phase1 UI 自保存形态的生产写入口等价）",
            listOf(groupId) to 0,
            preferences.getAlbumCleanupCheckpoint(albumId)
        )
        assertEquals("已处理记录必须恰为 g1", setOf(g1Key),
            preferences.getAlbumAcknowledgedCleanupMembers(albumId))
        assertTrue(
            "组不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
        Log.i(TAG, "cleanup state face seeded: group=$groupId albumId=$albumId acknowledged={g1} checkpoint=([G],0)")

        // ---- 步骤1a：真实系统机制建立 PARTIAL(B)（#57 原语，FPF Phase2/B2MR 同链路）----
        ui.launch()
        ui.waitFor(GATE_BUTTON, timeoutMs = 30000)
        ui.tap(GATE_BUTTON)
        ui.awaitAny(ui.DIALOG_CANDIDATES, tag = "stale-permission-dialog", timeoutMs = 20000)
        ui.tapDialogActionAndAwaitPicker(ui.SELECT_PHOTOS_CANDIDATES, tag = "stale-select-photos")
        ui.collectGridCells(minCount = GRID_TILE_COLORS.size)
        // 网格时间倒序 = [G3,B2,B1,G2,G1,N1,S1]（g3 mtime 最晚且最后入库，类头注）
        ui.driveCheckedCells(expected = setOf(1, 2), tileColors = GRID_TILE_COLORS)
        ui.confirmSelection()

        val granted = awaitDeadline("stale-partial-grant") {
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_DENIED
        }
        assertTrue("确认后必须授予 VISUAL_USER_SELECTED 且不得授予 FULL", granted)
        assertEquals(
            MediaPermissionPolicy.MediaAccessLevel.PARTIAL,
            MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )
        // 地面真值：应用可见恰 = {b1,b2}（选择集过滤语义）
        awaitCollectionFace(B_NAMES.toSet(), timeoutMs = 20000, tag = "partial-B")
        // deck 渲染（PARTIAL 时代批次 = B；初始加载已过接缝——门未武装不暂停）
        ui.waitFor(ui.DECK_MARKER, timeoutMs = 30000)
        awaitAnyNode(B_NAMES, timeoutMs = 30000, tag = "stale-deck-partial")
        val pid0 = android.os.Process.myPid()
        assertEquals("应用必须运行在 instrumentation 同进程（pidof 恒等）", pid0, appPid())
        Log.i(TAG, "step1a PARTIAL(B) established: visible={b1,b2}, deck rendered, pid=$pid0")

        // ---- 步骤1b：武装 + 回前台刷新 → 旧扫描停在可控位（parkedRows==2）----
        MediaLoadTestHook.armOnce()
        Log.i(TAG, "stale-arm-boundary: gate armed, expecting next revision++ scan to park")
        ui.shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(2000)
        ui.launch()
        ui.waitFor(ui.DECK_MARKER, timeoutMs = 30000)
        assertEquals("回前台必须同进程", pid0, appPid())
        val parked = awaitDeadline("stale-park", timeoutMs = 30000) {
            MediaLoadTestHook.parkedRows() == 2
        }
        assertTrue(
            "旧扫描必须停在可控位（parkedRows()==2 = PARTIAL 选中集合规模：暂停者=" +
                "旧选择集合时代扫描且其读取已返回；armed=" +
                "${MediaLoadTestHook.isReleased()}）",
            parked
        )
        Log.i(TAG, "step1b old scan parked at production seam: parkedRows=2 (read returned, projection held)")

        // ---- 步骤2：会话内注入 40 填充 → store 47；应用可见仍恰={b1,b2}；授权升级 ----
        injectFixturesBulk(
            FILLER_NAMES,
            touchTimeOf = { name ->
                val idx = Regex("stale-fill-(\\d+)\\.png").find(name)?.groupValues?.get(1)?.toInt()
                    ?: error("unexpected filler name $name")
                fillerTouchTime(idx)
            },
            expectedDirTotal = NAMED_NAMES.size + FILLER_NAMES.size
        )
        val storeAfterFill = shellStoreRows()
        assertEquals(
            "注入填充后 store 必须恰为 47 行（7 命名 + 40 填充，无多余无缺失）",
            FULL_EXPECTED_NAMES, storeAfterFill.keys
        )
        // 无 ContentObserver：注入不动界面——可见集合仍恰 = B（选择集过滤语义）
        awaitCollectionFace(B_NAMES.toSet(), timeoutMs = 15000, tag = "after-fill-still-B")
        // deck 界面面：填充媒体不得出现（5s 不变量窗）+ deck 标记保持
        ui.awaitGone(fillerName(1), timeoutMs = 5000)
        ui.awaitGone(fillerName(FILLER_COUNT), timeoutMs = 5000)
        ui.waitFor(ui.DECK_MARKER, timeoutMs = 10000)
        // 会话内授权升级（grant 不杀进程 #52 口径；应用前台持 deck）
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
        assertEquals("grant 不杀进程（#52 口径）：授权后进程必须存活且 pid 恒等", pid0, appPid())
        Log.i(TAG, "step2 fillers injected (store=47), in-session grant applied, level=FULL")
        // 升级重启：onResume 级别变化分支 revision++ → 键控重启（被暂停旧扫描被取消，
        // 取消被 NonCancellable 推迟至释放；新扫描全池重查）
        ui.shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(2000)
        ui.launch()
        ui.waitFor(ui.DECK_MARKER, timeoutMs = 30000)
        assertEquals("升级重启必须同进程（无进程死亡）", pid0, appPid())

        // ---- 步骤3a：新扫描提交 → 集合面终态 = 47 名全集（哨兵存在性；无重复无缺失）----
        awaitCollectionFace(FULL_EXPECTED_NAMES, timeoutMs = 30000, tag = "full-47")
        val fullFace = visibleDisplayNameList(ctx)
        assertTrue(
            "FULL 集合面不得有重复行（list=${fullFace.size} set=${fullFace.toSet().size}）",
            fullFace.size == fullFace.toSet().size
        )
        Log.i(TAG, "step3a collection face = full 47 names (sentinel s1 included)")

        // ---- 步骤3b：deck 存活行同步（组合观察窗，B2MR 5b 同款：不白屏不回退）----
        val observeEnd = SystemClock.uptimeMillis() + 6000
        var appWindowOk = 0
        while (SystemClock.uptimeMillis() < observeEnd) {
            val root = ui.ins.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString() == ctx.packageName) {
                appWindowOk++
                if (ui.find(root, GATE_BUTTON) != null) {
                    ui.dumpTree("unexpected-gate-after-upgrade")
                    ui.saveScreenshot("unexpected-gate-after-upgrade")
                    error("升级后门控屏不得出现（级别已 FULL）")
                }
                if (ui.find(root, ui.DECK_MARKER) == null) {
                    ui.dumpTree("unexpected-deck-marker-missing")
                    ui.saveScreenshot("unexpected-deck-marker-missing")
                    error("升级后 deck 标记必须持续在窗（存活行同步不得白屏）")
                }
            }
            SystemClock.sleep(200)
        }
        assertTrue(
            "观察窗内必须存在被测应用窗口（有效性门，appWindowOk=$appWindowOk）",
            appWindowOk > 0
        )
        awaitAnyNode(B_NAMES, timeoutMs = 10000, tag = "stale-deck-kept-after-upgrade")
        Log.i(TAG, "step3b deck kept alive through upgrade (no gate, no blank-out, window ok)")

        // ---- 步骤3c：消费池边界断言（batch-site-4 池集相等，B2MR 5c 同款）----
        // 真实 Settings：deck 顶栏"设置"→ Settings → 推荐模式行 → sheet → 现值
        // "随机漫步"重选（onRecommendModeChange → recommendModeRefreshKey++ →
        // deck batch-site-4 重取批）。边界标记紧贴触发动作之前写入。
        openSettingsFromDeck()
        assertTrue(
            "推荐模式行（滚动定位 + sheet 打开核验）失败",
            ui.tapAnyScrollableToMarker(
                candidates = listOf("推荐模式"),
                verifyMarker = "真正随机抽取",
                tag = "stale-recommend-row",
                maxScrolls = 4
            )
        )
        val option = ui.waitFor("真正随机抽取")
        Log.i(TAG, "$STALE_BOUNDARY_MARKER before recommend-mode reselect tap")
        ui.clickNode(option, "stale-option-random-walk")
        FullPartialFullUi.awaitDismissed("真正随机抽取", tag = "stale-sheet-dismissed", timeoutMs = 10000)

        // 池集相等：动作边界后批次日志行 id 与 47 行 store 全集集合相等（截止轮询 30s）。
        val expectedPoolIds = FULL_EXPECTED_NAMES.map { name ->
            storeAfterFill.getValue(name)
        }.toSet()
        val poolDeadline = SystemClock.uptimeMillis() + 30000
        var lastLine = ""
        var batchIds: Set<String> = emptySet()
        while (SystemClock.uptimeMillis() < poolDeadline) {
            lastLine = bufferLinesAfterMarker(STALE_BOUNDARY_MARKER)
                ?.lastOrNull { it.contains(BATCH_LOG_ANCHOR) } ?: ""
            batchIds = parseBatchIds(lastLine)
            if (batchIds == expectedPoolIds) break
            SystemClock.sleep(300)
        }
        if (batchIds != expectedPoolIds) {
            ui.dumpTree("timeout-stale-pool")
            ui.saveScreenshot("timeout-stale-pool")
            error(
                "动作边界后批次日志池集相等失败（消费面未在本次动作后承接 FULL 全池；" +
                    "含动作后无新批次事件/仅有动作前日志的情形）：\n" +
                    "lastLine=$lastLine\nbatchIds(${batchIds.size})=$batchIds\n" +
                    "expectedIds(${expectedPoolIds.size})"
            )
        }
        Log.i(TAG, "step3c post-boundary batch-site-4 pool equality: ${batchIds.size} ids == full 47 pool")

        // ---- 返回 deck：BACK（SETTINGS→DECK 生产回退）——释放与观察窗在 deck 前景执行 ----
        // （Settings 前景下 deck 为 backgroundContent，标记不在活动窗树内——观察窗的
        // deck 树断言必须在 deck 前景执行；B2MR 步骤5 同款 BACK 回退。）
        ui.shell("input keyevent KEYCODE_BACK")
        ui.waitFor(ui.DECK_MARKER, timeoutMs = 30000)
        awaitAnyNode(NAMED_NAMES + FILLER_NAMES, timeoutMs = 15000, tag = "stale-deck-full-pool")
        assertEquals("步骤3→4 之间必须同进程", pid0, appPid())
        Log.i(TAG, "back on deck consuming full pool before release (pid=$pid0)")

        // ---- 步骤4：释放旧扫描，确认旧读取确实返回（结果在手上；deck 前景执行）----
        Log.i(TAG, "$STALE_RELEASE_MARKER before release")
        MediaLoadTestHook.release()
        val released = awaitDeadline("stale-release", timeoutMs = 20000) {
            MediaLoadTestHook.isReleased() &&
                ui.shell("logcat -d -s MediaLoadTestHook").contains("resumed rows=2")
        }
        assertTrue(
            "释放后必须确认旧读取确实返回（isReleased() 且 logcat 出现 resumed rows=2" +
                "——rows=2 即旧选择集合投影；释放不改变提交归属，提交保护归生产链" +
                "恢复期取消检查）",
            released
        )
        assertEquals("释放不产生新的 revision/扫描（无 Home/am start 动作）", pid0, appPid())
        Log.i(TAG, "step4 old read surfaced: isReleased=true, logcat 'resumed rows=2' confirmed")

        // ---- 步骤5a：观察窗（≥5s，deck 前景）：取消不误报失败 + 可见性不变量 ----
        // deck 标记在窗；无门控（hasPermission 保持）；无"回收站记录未能读取"对话框
        // （onError 呈现面）。集合面（MediaStore 原始查询）在此窗为**可见性不变量**
        // （旧投影 apply 只改应用内存 allImages，不动 MediaStore——覆盖检测器是
        // 步骤5b 的确定性重取批，见下）。
        val staleObserveEnd = SystemClock.uptimeMillis() + 5000
        var staleWindowOk = 0
        var lastFace: List<String>? = null
        while (SystemClock.uptimeMillis() < staleObserveEnd) {
            val root = ui.ins.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString() == ctx.packageName) {
                if (ui.find(root, GATE_BUTTON) != null) {
                    ui.dumpTree("unexpected-gate-after-release")
                    ui.saveScreenshot("unexpected-gate-after-release")
                    error("释放后门控屏不得出现（hasPermission 必须保持 FULL）")
                }
                if (ui.find(root, ui.DECK_MARKER) == null) {
                    ui.dumpTree("unexpected-deck-marker-missing-after-release")
                    ui.saveScreenshot("unexpected-deck-marker-missing-after-release")
                    error("释放后 deck 标记必须持续在窗（取消不得重置消费面）")
                }
                if (ui.find(root, RECYCLE_ERROR_DIALOG) != null) {
                    ui.dumpTree("unexpected-recycle-error-dialog")
                    ui.saveScreenshot("unexpected-recycle-error-dialog")
                    error("释放后不得出现'回收站记录未能读取'对话框（取消不得误报加载失败）")
                }
                val face = visibleDisplayNameListOrNull(ctx)
                if (face != null) {
                    lastFace = face
                    if (face.toSet() != FULL_EXPECTED_NAMES || face.size != FULL_EXPECTED_NAMES.size) {
                        ui.dumpTree("unexpected-store-face-after-release")
                        ui.saveScreenshot("unexpected-store-face-after-release")
                        error(
                            "释放后 MediaStore 可见面变化（应用动作不得增删媒体行）：" +
                                "expected=47 行 actual=${face.size} 行 actualSet=${face.toSet()}"
                        )
                    }
                }
                staleWindowOk++
            }
            SystemClock.sleep(200)
        }
        assertTrue(
            "释放后观察窗必须存在被测应用窗口（有效性门，staleWindowOk=$staleWindowOk）",
            staleWindowOk > 0
        )
        Log.i(TAG, "step5a stale observe window ok: deck kept, no gate, no error dialog, store face 47 ($staleWindowOk polls, lastFace=${lastFace?.size})")

        // ---- 步骤5b：旧集合不覆盖（确定性重取批）+ 取消不落 error 日志 ----
        // **覆盖检测器**：旧投影 apply 只改应用内存 allImages（MediaStore 面不变），
        // 故经真实 Settings 推荐模式现值重选（第二次）触发 deck 从**当前已提交池**
        // 重取批——若旧扫描释放后曾提交 2 行覆盖（allImages→{b1,b2}），重取批/
        // 同步批次将出现 2 行批次事件（红灯）；池完好则重取批 = 47 池（池 ≤
        // batchSize 50 → 整池，随机漫步自清冷却，重取确定性成立）。断言：释放标记
        // 后**全部** DeckScreen 批次行 id 集 == 47 池集，且**至少存在一条**（重取
        // 动作保证新事件；无事件/仅动作前日志 = 红灯 fail-closed）。错误面：
        // TabulaApp "Could not load/reconcile recycle bin" 必须缺席（
        // CancellationException 原样重抛不落 onError——PR50 语义的设备面）。
        openSettingsFromDeck()
        assertTrue(
            "推荐模式行（第二次重取批，滚动定位 + sheet 打开核验）失败",
            ui.tapAnyScrollableToMarker(
                candidates = listOf("推荐模式"),
                verifyMarker = "真正随机抽取",
                tag = "stale-recommend-row-2",
                maxScrolls = 4
            )
        )
        val option2 = ui.waitFor("真正随机抽取")
        Log.i(TAG, "stale-post-release-refetch-boundary: before second reselect tap")
        ui.clickNode(option2, "stale-option-random-walk-2")
        FullPartialFullUi.awaitDismissed("真正随机抽取", tag = "stale-sheet-dismissed-2", timeoutMs = 10000)

        val afterRelease = bufferLinesAfterMarker(STALE_RELEASE_MARKER)
            ?: error("释放边界标记未在 logcat 缓冲中找到（被环淘汰或未写入）——fail-closed")
        val postReleaseBatchLines = afterRelease.filter { it.contains(BATCH_LOG_ANCHOR) }
        val refetchDeadline = SystemClock.uptimeMillis() + 30000
        var latestPostIds: Set<String> = emptySet()
        while (SystemClock.uptimeMillis() < refetchDeadline) {
            val lines = bufferLinesAfterMarker(STALE_RELEASE_MARKER) ?: emptyList()
            val batchLines = lines.filter { it.contains(BATCH_LOG_ANCHOR) }
            latestPostIds = batchLines.lastOrNull()?.let { parseBatchIds(it) } ?: emptySet()
            if (latestPostIds == expectedPoolIds &&
                batchLines.all { parseBatchIds(it) == expectedPoolIds }
            ) break
            SystemClock.sleep(300)
        }
        if (latestPostIds != expectedPoolIds ||
            bufferLinesAfterMarker(STALE_RELEASE_MARKER)!!
                .filter { it.contains(BATCH_LOG_ANCHOR) }
                .any { parseBatchIds(it) != expectedPoolIds }
        ) {
            ui.dumpTree("stale-overwrite-or-no-refetch")
            ui.saveScreenshot("stale-overwrite-or-no-refetch")
            val lines = bufferLinesAfterMarker(STALE_RELEASE_MARKER)!!.filter { it.contains(BATCH_LOG_ANCHOR) }
            error(
                "释放后批次池断言失败（旧扫描释放后提交 2 行覆盖 / 或重取批未承接 47 池 / " +
                    "或释放后无新批次事件）：\n" +
                    "postReleaseBatchLines(${lines.size})=${lines.joinToString("\n")}\n" +
                    "expectedIds(${expectedPoolIds.size})"
            )
        }
        Log.i(TAG, "step5b post-release refetch = full 47 pool, no 2-row batch event (stale projection never applied)")
        val errorLines = afterRelease.filter { it.contains(LOAD_ERROR_LOG) } +
            bufferLinesAfterMarker(STALE_RELEASE_MARKER)!!.filter { it.contains(LOAD_ERROR_LOG) }
        assertTrue(
            "取消不得落 error 态（TabulaApp '$LOAD_ERROR_LOG' 必须缺席；" +
                "found=${errorLines.joinToString(" || ")}）",
            errorLines.isEmpty()
        )
        Log.i(TAG, "step5b post-release buffer clean: no TabulaApp load error line (cancellation rethrown as-is)")

        // ---- 步骤5c：清理状态面读回（新 AlbumManager 实例 + AppPreferences 持久层）----
        // 旧扫描在暂停前已执行过 albumManager.initialize(旧投影)（接缝位于
        // initialize 之后）；释放后组键/断点/已处理读回原样 = stale initialize
        // 无持久污染（FPF Phase3 Continuation 读回模式）。
        val freshManager = AlbumManager(ctx)
        assertEquals("组键必须原样（stale initialize 无持久污染）", groupKeys,
            preferences.getGroupImageIds(albumId, groupId))
        assertEquals(
            "断点必须原样（([G],0)——stale initialize/释放路径不得改写）",
            listOf(groupId) to 0,
            preferences.getAlbumCleanupCheckpoint(albumId)
        )
        assertEquals("已处理记录必须原样", setOf(g1Key),
            preferences.getAlbumAcknowledgedCleanupMembers(albumId))
        assertTrue(
            "组不得被标记永久完成",
            preferences.getAlbumPermanentlyProcessedGroups(albumId).isEmpty()
        )
        Log.i(TAG, "fresh AlbumManager instance constructed (persistence path = AppPreferences): albums flow=${freshManager.albums.value.size} entries")
        Log.i(TAG, "step5c cleanup state face read back unchanged: group keys/checkpoint([G],0)/acknowledged={g1}")

        // ---- 收尾核对：哨兵 s1 行在库（集合面已含；shell 面双面核对 FPF 同口径）----
        val finalRows = shellStoreRows()
        assertTrue("哨兵 s1 必须在库（shell 面）", SENTINEL_NAME in finalRows)
        assertEquals("终态 store 必须仍为 47 行（应用动作不得增删媒体行）", FULL_EXPECTED_NAMES, finalRows.keys)
        assertEquals("全程必须同进程（无进程死亡/无隐性重启）", pid0, appPid())

        // ---- 用例末自清（目录整体删除 + 逐项核对；@After 另有幂等兜底）----
        val residual = cleanupAllFixtures()
        assertTrue("用例末夹具必须全部删除：residual=$residual", residual.isEmpty())
        Log.i(TAG, "complete: parked stale scan released after 47-pool commit; old projection never applied, cancellation never reported as load failure (real-wiring level, seam-constructed race)")
    }

    /**
     * 残留兜底（幂等）：用例任何路径退出后按目录整体删除夹具行与文件，防死行
     * 残留污染同宿主后续 probe 段（b2mr/b2pr 按名兜底同款纪律）。
     */
    @After fun cleanup() {
        val residual = cleanupAllFixtures()
        Log.i(TAG, "after cleanup residual=$residual")
    }
}
