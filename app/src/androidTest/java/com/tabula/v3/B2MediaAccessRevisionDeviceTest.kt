package com.tabula.v3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.data.preferences.AppPreferences
import com.tabula.v3.data.preferences.SwipeStyle
import com.tabula.v3.FullPartialFullStateContract.visibleDisplayNameList
import com.tabula.v3.FullPartialFullStateContract.visibleDisplayNameListOrNull
import com.tabula.v3.permission.MediaPermissionPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PR72/PR79 后续（2026-11-14）：真实 MainActivity 权限变化（**PARTIAL(B)→FULL
 * 级别升级分支**）触发扫描重启的设备级接线验收——升级前 deck 已持 PARTIAL 时代
 * 批次（B={b1,b2}），会话内 shell `pm grant READ_MEDIA_IMAGES` → ON_RESUME 修订
 * 重查（applyAccessLevel 级别变更分支 mediaAccessRevision++）→ TabulaApp
 * LaunchedEffect(revision) 键控重启（取消在途扫描）→ loadAndApplyMediaSnapshot
 * 提交全池 → deck 消费面承接新池。**本类验证的是真实接线的信息流终态**：
 * 最终生效的提交池 = FULL 全池（47 名）且被 deck 消费面承接——"在途旧读取
 * 晚返回不得覆盖新权限态"的竞争**语义本身不由本类断言**（该语义由单测级
 * MediaSnapshotLoadRaceTest（PR50）+ FPF Phase3 判据 10 受控竞争门覆盖，
 * 见下方"如实登记"）。
 *
 * 与 B2PermissionRefreshDeviceTest 的分工：该类步骤3 以 NONE→门控→grant→重启
 * 为载体（首次组合与 revision 递增叠加，无可被覆盖的旧批次）；本类升级前 deck
 * 已持真实批次与活动组合——同步路径（DeckScreen LaunchedEffect(allImages,...)
 * 存活行保留）与"终局提交池不得为 PARTIAL 陈旧集合"的**终态判别**在设备层
 * 才有可观测载体。级别分支覆盖矩阵：
 * NONE→FULL（B2PermissionRefresh 步骤3）/ FULL→NONE 进程边界（其步骤2）/
 * **PARTIAL→FULL 同进程（本类）** 与 PARTIAL 时代前台刷新（本类中途步）。
 *
 * 生产接线（读码实证，本类验证的信息流）：
 * onResume → checkPermission(foregroundRefresh=true)（MainActivity.kt:342-352）
 * → applyAccessLevel（:395-407）：级别变化（PARTIAL→FULL）或级别不变+带权限
 * 回前台 → mediaAccessRevision++ → TabulaApp LaunchedEffect(revision,...)
 * 键控重启（MainActivity.kt:576-620）→ loadAndApplyMediaSnapshot（:183-224，
 * verbatim 提取）
 * → apply 提交 allImages → DeckScreen 同步（DeckScreen.kt:720+ 存活行保留）
 * / 重取批（batch-site-3/4，DeckScreen.kt:540+/600+）。取消语义（键变化 →
 * effect 取消 → 扫描体经 CancellationException 原样退出，apply/error 不为被
 * 取消扫描执行）为**读码+PR50 单测钉住的生产语义**，本类不作设备级断言
 * （自然扫描毫秒级、无注入点，取消是否命中在途扫描取决于时序）。
 *
 * 场景单方法（预期 case 数 = **1**，probe 段
 * "B2 media-access-revision PARTIAL->FULL device verification"，OK(1) 精确门）：
 * 1. 真实系统机制建立 PARTIAL(B)（ForegroundPermissionUiTest/FPF Phase2 #57
 *    原语：系统权限对话框 "Select photos" → 照片选择器 7 格 → 像素驱动只选
 *    {1,2}=b2,b1 → 确认提交）——**PARTIAL 的"用户选中集合"不可经 pm 确定性
 *    夹具化（既有登记口径）**，选择器路径是唯一确定性构造方式；地面真值
 *    可见恰={b1,b2}，deck 渲染批次。
 * 2. 会话内 shell 注入 **40 张填充媒体**（shell 身份 base64 文本通道）：
 *    MediaStore 行 = 47，应用可见集合**仍恰={b1,b2}**（选择集过滤语义 +
 *    无 ContentObserver——会话内注入唯一重扫触发 = ON_RESUME，注入不动界面）。
 *    40 张的意义：池 47 > DEFAULT_BATCH_SIZE 15（AppPreferences.kt:1222）→
 *    推荐引擎走"冷却保存而非自清"路径（clearAllPickRecords 仅在
 *    allImages.size <= batchSize 时触发，RecommendationEngine.kt:253-258）；
 *    同时 batchSize 预置 50（coerceIn(5,50)）→ 池 47 ≤ 50 → 任一次重取批 =
 *    整池（随机漫步无冷却残留），池集相等断言因此确定性成立。
 * 3. PARTIAL 时代前台刷新重扫（Home→am start → onResume 级别不变+
 *    foregroundRefresh → revision++ → 重扫提交 {b1,b2}）——"旧时代扫描"基准。
 * 4. 会话内授权升级（**grant 不杀进程 #52 口径**，应用前台持 deck 时授权；
 *    权限变化对应用不可见——无监听，直至下一次 onResume）→ 级别=FULL。
 * 5. 升级重启 ×2（Home→am start，两次各 revision++；第二次重启意在提高与
 *    在途扫描相撞的机会——若第一次扫描仍在途则被第二次键重启取消，但**是否
 *    真有在途扫描取决于时序、不可确定断言**，登记为时序依赖启发式而非判别
 *    器）→ 断言链：
 *    a. **集合面终态**：应用可见集合恰 = 47 名全集（g1,g2,g3,n1,b1,b2 + 哨兵
 *       s1 + 40 填充），无重复无缺失（FULL 权限语义的全集暴露，s1 存在性
 *       核对 = FPF 判据 8 同口径）；
 *    b. deck 存活行同步：b1/b2 批次保持可见、门控屏不得出现（升级不白屏
 *       不回退）；组合观察窗（应用包窗口有效性 + 不变量，Phase2 同窗纪律）；
 *    c. **消费面承接新池（生产批次日志池集相等）**：经真实 Settings
 *       （推荐模式 sheet 现值重选 → onRecommendModeChange →
 *       recommendModeRefreshKey++ → deck batch-site-4 重取批）触发 deck 从
 *       **当前已提交池**重取——批次日志行 id 与 47 行全集**集合相等**。这是
 *       **终局提交面**判别：最后一次生效的提交池必须为 FULL 全池（若 PARTIAL
 *       时代陈旧快照是最后一次提交，重取批将只含 2 行 id 红灯）；**瞬态覆盖
 *       后被新扫描纠正**的形态不可由终态断言检测（如实登记，PR50 单测覆盖
 *       该语义）。批次事件只认本次动作边界（REFETCH_BOUNDARY_MARKER）之后
 *       的新事件——拒绝"仅有动作前正确日志"。全程同进程（pidof 恒等）——进程死亡会
 *       连带 instrumentation 死亡，pid 断言留证无隐性重启。
 *
 * 如实登记（确定性边界，与执行指令口径一致）：
 * - "在途扫描被新键取消、旧读取晚返回不覆盖新权限态"的严格竞态形态设备层
 *   不可确定性构造（生产 load 无注入点、自然扫描毫秒级不可控；两次重启间
 *   键控取消路径是否真实命中取决于在途时序，如实登记）——该语义由单测级
 *   MediaSnapshotLoadRaceTest（PR50）+ FullPartialFullPhase3Test 判据 10
 *   受控竞争门（真实生产链 NonCancellable 晚返回形态）覆盖；本类验证真实
 *   接线的信息流终态 + 会话内已提交池的终局判别（最终提交池=FULL 全池），
 *   **不含**瞬态覆盖检测（被新扫描纠正的旧写入不可由终态断言发现）。
 * - 消费面批次断言只认**本次动作边界**（REFETCH_BOUNDARY_MARKER，动作前
 *   写入测试自身 TAG）之后的 DeckScreen 批次事件：单 logcat 主缓冲 append
 *   序在同一 dump 内单调可靠；不用墙钟时间戳（NTP/校时跳变风险+brief 格式
 *   无毫秒）；不用 pid 过滤（序列边界已排除跨时污染）。边界被缓冲环淘汰时
 *   查找返回 null → 断言 fail-closed。
 * - 47 卡批次下 a11y 树仅暴露当前+peek ≤3 槽（Phase3 边界 2 同口径）——
 *   具体夹具名节点可见性依赖洗牌位，确定性证据由集合面全集 + 批次日志池集
 *   相等承担；deck 节点断言取"任一夹具名可见"（消费面非空 + 新池内容到达）。
 * - Settings 推荐模式现值重选不改模式（随机漫步→随机漫步），仅借
 *   onRecommendModeChange 通道触发重取批（SettingsScreen OptionItem onClick
 *   无条件回调）；deck 在 SETTINGS 前景下保持组合（MainActivity.kt:1656
 *   deckContent 为 backgroundContent），重取批在同组合内完成。
 * - PARTIAL 建立经真实选择器（像素勾选驱动）——CI 时长 ≈1-1.5 分钟，与本类
 *   其余步骤合计 <6 分钟；probe 侧预算同步上调（run-volume-device-probe.py
 *   wrapper 1200s→1500s + device job timeout 30→40，e95f705/2be08ad 同款
 *   既有口径）。
 *
 * 夹具纪律：全部 shell 身份（base64 **文本**通道 on-device 落盘 + scan_file
 * 入库 + 广播兜底 + 行截止轮询 + shell 行核对，FullPartialFullStateContract
 * .ensureG3Fixture 同款——字节不经 UiAutomation，run 42476083194/42477634711
 * 截断实证口径）。7 张命名夹具 Pictures/B2MediaAccessRevisionFixtures/
 * （mtime 序 = s1<n1<g1<g2<b1<b2<g3 且 g3 最后入库——时间倒序与 _id 倒序两
 * 模型同网格序，B={1,2} 不依赖未实证排序模型，FPF 类头同款设计）；填充 40
 * 张同目录 b2m-fill-01..40（mtime 晚于全部命名夹具）。用例末自清（按目录
 * 整体删除 + 逐项核对）+ @After 幂等兜底，不残留到后续 probe 段。
 * 不动 app/src/main、app/src/test。
 */
@RunWith(AndroidJUnit4::class)
class B2MediaAccessRevisionDeviceTest {
    private val TAG = "B2MediaRevision"
    private val ui get() = PartialSelectionUi
    private val ctx get() = ui.ctx

    // ==================== 夹具契约（shell 身份，Pictures/B2MediaAccessRevisionFixtures/） ====================

    private val FIXTURE_DIR = "Pictures/B2MediaAccessRevisionFixtures/"
    private val FIXTURE_ABS_DIR = "/sdcard/Pictures/B2MediaAccessRevisionFixtures"

    /** 命名夹具 7 张（语义名沿用 FPF 模型：哨兵 s1 不入集合断言集，仅存在性）。 */
    private val SENTINEL_NAME = "b2mr-s1.png"
    private val N1_NAME = "b2mr-n1.png"
    private val G1_NAME = "b2mr-g1.png"
    private val G2_NAME = "b2mr-g2.png"
    private val G3_NAME = "b2mr-g3.png"
    private val B1_NAME = "b2mr-b1.png"
    private val B2_NAME = "b2mr-b2.png"

    /** B 集合（PARTIAL 选中集）与命名全集（哨兵除外）。 */
    private val B_NAMES = listOf(B1_NAME, B2_NAME)
    private val MEMBER_NAMES = listOf(G1_NAME, G2_NAME, G3_NAME, N1_NAME, B1_NAME, B2_NAME)
    private val NAMED_NAMES = listOf(SENTINEL_NAME) + MEMBER_NAMES

    /**
     * mtime（touch -t）序 = s1(11-01)→n1(11-03)→g1(11-05)→g2(11-06 12:00)→
     * b1(11-07)→b2(11-08 20:00)→g3(11-09 09:00 最晚)；种子写入序同序 → g3 行
     * _id 最大 → "时间倒序"与"_id 倒序"两排序模型给出同一选择器网格序
     * [G3,B2,B1,G2,G1,N1,S1] → B = 网格索引 {1,2}（FPF StateContract 类头同款）。
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

    /**
     * 选择器网格位置 → 瓷片颜色占位（FPF GRID_TILE_COLORS 语义：序 [G3,B2,B1,
     * G2,G1,N1,S1]）。本类全部夹具同用纯绿 PNG——勾选态判定不依赖瓷片颜色
     * （readCheckedStateByPixel 以角标白色笔画为判据，瓷片色仅作数量核对输入，
     * PartialSelectionUi 实证口径），故占位常量即可。
     */
    private val GRID_TILE_COLORS = listOf(0xFF00FF00, 0xFF00FFFF, 0xFFFFFF00, 0xFFFF00FF, 0xFFFF0000, 0xFF0000FF, 0xFF888888).map { it.toInt() }

    /** 填充媒体 40 张（池 47 = 7+40 > DEFAULT_BATCH_SIZE 15；见类头设计说明）。 */
    private val FILLER_COUNT = 40
    private fun fillerName(index: Int): String = "b2m-fill-%02d.png".format(index)
    private val FILLER_NAMES = (1..FILLER_COUNT).map { fillerName(it) }

    /** 填充 mtime：2026-11-10 09:01..09:40——均晚于 g3（202611090900），同月不漂移。 */
    private fun fillerTouchTime(index: Int): String = "2026111009%02d".format(index)

    /** FULL 地面真值全集 = 47 名（7 命名 + 40 填充），无重复无缺失。 */
    private val FULL_EXPECTED_NAMES: Set<String> = (NAMED_NAMES + FILLER_NAMES).toSet()

    /** 96x96 纯绿 PNG 的 base64（FullPartialFullStateContract.G3_PNG_BASE64 同款同源）。 */
    private val PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAIAAABt+uBvAAABC0lEQVR4nO3OMQ0AIAAEsfdvGhyw9gaSCuh29r34QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UGcH8T5QZwfxPlBnB/E+UHbBY393g69gixzAAAAAElFTkSuQmCC"

    /** 权限门控屏按钮文本（PermissionRequestScreen，MainActivity.kt:1906）。 */
    private val GATE_BUTTON = "授权访问照片"

    /** 生产批次重取日志锚（DeckScreen.logBatchRows；batch-site-4 = 推荐模式刷新路径）。 */
    private val BATCH_LOG_ANCHOR = "currentBatch<-[batch-site-4]:"

    /**
     * 本次动作边界标记：消费面断言只认该标记**之后**的 DeckScreen 批次事件。
     * 可靠性依据（2026-11-15 收口，替代 whole-buffer lastOrNull）：logcat 主
     * 缓冲为单进程追加写，同一 `logcat -d` dump 内行序单调——标记之后出现的
     * 批次行必然晚于标记写入（程序顺序+缓冲 append 序保证），据此拒绝"仅有
     * 动作前正确日志"（含上一 probe 段遗留行：缓冲跨 pm clear/跨段持续，
     * T06/FPF 段的 batch-site-4 行与本用例更早步骤的行都可能成为
     * whole-buffer 尾行）。不用墙钟时间戳边界（logcat 时间=墙钟，NTP/校时
     * 跳变风险，默认 brief 格式无毫秒）；不用 pid 过滤（序列边界已排除跨时
     * 污染）。标记被缓冲环淘汰 → indexOfLast=-1 → 返回 null → 断言
     * fail-closed（报错而非放行旧日志）。
     */
    private val REFETCH_BOUNDARY_MARKER = "b2mr-refetch-boundary:"

    /** batchSize 预置（coerceIn(5,50) 合法上界）：池 47 ≤ 50 → 重取批 = 整池。 */
    private val BATCH_SIZE_PREF = 50

    @Before fun setup() {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticMediaProbe") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        check(Build.VERSION.SDK_INT >= 34) { "PARTIAL selection semantics require API 34+" }
    }

    // ==================== 共享原语（ensureG3Fixture 模式 + 批量通道） ====================

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
     * shell 面 bulk 查询：store 全部图片行 (display_name → _id) 映射。
     * 单次 content query 承载全集核对与批次日志按行 id 比对（卷限定 uri 形式无关）。
     * 列分隔 = ", "（content query 实证格式），按列锚分别解析避免歧义。
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
     * 夹具批量注入（shell 身份，模板复制通道）：按名幂等预清（单脚本 for 循环
     * content delete——**不动目录内其他行**，两次注入共用目录互不破坏）→ 模板
     * base64 解码 + for 循环 cp 落盘（单 argv 体量恒定，40 文件不放大）→ 逐文件
     * touch -t → for 循环 scan_file 入库（宿主管线同款机制）→ bulk 行核对，缺行
     * 走 MEDIA_SCANNER_SCAN_FILE 广播兜底再轮询 → 目录文件计数核对（累计值，
     * 两批共用目录）。行所有权 = shell（文件由 shell UID 经 FUSE 落盘）——与宿主
     * adb push 同身份（PARTIAL 选择集语义可观测前提）。
     */
    private fun injectFixturesBulk(
        names: List<String>,
        touchTimeOf: (String) -> String,
        expectedDirTotal: Int
    ) {
        // 按名幂等预清（单脚本循环；不动目录内其他行）
        ui.shellScript(
            buildString {
                append("for n in ")
                names.forEach { append("$it ") }
                append("; do content delete --uri content://media/external/images/media ")
                append("--where \"_display_name=\$n AND relative_path='${FIXTURE_DIR.trimEnd('/')}'\"; ")
                append("done 2>&1")
            }
        )
        // 模板复制落盘（单脚本：模板 base64 解码 1 次 + cp 循环 + 逐文件 touch -t）
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
        // 批量 scan_file 入库
        ui.shellScript(
            "for f in $FIXTURE_ABS_DIR/*.png; do " +
                "content call --uri content://media/ --method scan_file --arg \$f; done 2>&1"
        )
        // 截止轮询 bulk 行出现；缺行走广播兜底再轮询
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
        return namesInFixtureDir(rows.keys)
    }

    private fun namesInFixtureDir(storeNames: Set<String>): List<String> =
        storeNames.filter { it.startsWith("b2mr-") || it.startsWith("b2m-fill-") }

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

    /** 最新生产批次日志行（logcat -d -s DeckScreen 全缓冲尾行；无则 null）。 */
    /**
     * 动作边界后最新批次日志行。同时 dump 测试自身 TAG 与 DeckScreen，按缓冲
     * 行序取最后一次边界标记之后的批次行；无边界标记（被环淘汰/未写入）或
     * 边界后无批次事件 → null（调用方按红灯处理，fail-closed）。
     */
    private fun latestBatchLogLineAfterBoundary(): String? =
        ui.shell("logcat -d -s DeckScreen $TAG").lineSequence().toList()
            .let { lines ->
                val markerIdx = lines.indexOfLast { it.contains(REFETCH_BOUNDARY_MARKER) }
                if (markerIdx < 0) null
                else lines.subList(markerIdx + 1, lines.size)
                    .lastOrNull { it.contains(BATCH_LOG_ANCHOR) }
            }

    /** 批次日志行 → 行 id 集合（uri 末段，形式无关；Phase3 边界 2 同款解析）。 */
    private fun parseBatchIds(line: String): Set<String> =
        line.substringAfter(BATCH_LOG_ANCHOR, "")
            .split(",")
            .mapNotNull { entry ->
                val id = entry.trim().substringBeforeLast(":").substringAfterLast("/", "")
                if (id.matches(Regex("\\d+"))) id else null
            }
            .toSet()

    /**
     * deck 顶栏"设置"入口 → Settings 前景（到达核验 = "推荐模式"/"显示设置" 行
     * 可见）。顶栏图标 contentDescription="设置"（DeckScreen.kt:2658）——树内
     * "设置"文本可能多节点命中（CardLifecycle 场景 2 同款歧义），逐候选点击
     * （clickNode 祖先回退）+ 到达核验，至多 4 候选；全部失败留证报错。
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
                ui.clickNode(node, "b2mr-open-settings")
                SystemClock.sleep(800)
                val after = ui.ins.uiAutomation.rootInActiveWindow
                if (ui.find(after, "推荐模式") != null || ui.find(after, "显示设置") != null) {
                    Log.i(TAG, "settings screen reached (candidate clicked)")
                    return
                }
            }
            SystemClock.sleep(300)
        }
        ui.dumpTree("timeout-b2mr-settings-entry")
        ui.saveScreenshot("timeout-b2mr-settings-entry")
        error("Settings 前景未到达（顶栏入口点击后推荐模式/显示设置行均不可见）")
    }

    // ==================== 测试主体（单方法，probe OK(1) 精确门） ====================

    @Test
    fun partialToFullUpgradeViaInSessionGrantRerunsScanAndConsumesFullPool() {
        // ---- 前置：无任何照片权限（级别 NONE；pm clear + 宿主兜底 revoke 后裸状态）----
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
        AppPreferences.getInstance(ctx).clearAllAlbumCleanupState()

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

        // ---- 步骤1：真实系统机制建立 PARTIAL(B)（#57 原语，FPF Phase2 同链路）----
        ui.launch()
        ui.waitFor(GATE_BUTTON, timeoutMs = 30000)
        ui.tap(GATE_BUTTON)
        ui.awaitAny(ui.DIALOG_CANDIDATES, tag = "b2mr-permission-dialog", timeoutMs = 20000)
        ui.tapDialogActionAndAwaitPicker(ui.SELECT_PHOTOS_CANDIDATES, tag = "b2mr-select-photos")
        ui.collectGridCells(minCount = GRID_TILE_COLORS.size)
        // 网格时间倒序 = [G3,B2,B1,G2,G1,N1,S1]（g3 mtime 最晚且最后入库，类头注）
        ui.driveCheckedCells(expected = setOf(1, 2), tileColors = GRID_TILE_COLORS)
        ui.confirmSelection()

        val granted = awaitDeadline("b2mr-partial-grant") {
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
        // deck 渲染（PARTIAL 时代批次 = B）
        ui.waitFor(ui.DECK_MARKER, timeoutMs = 30000)
        awaitAnyNode(B_NAMES, timeoutMs = 30000, tag = "b2mr-deck-partial")
        val pid0 = android.os.Process.myPid()
        assertEquals("应用必须运行在 instrumentation 同进程（pidof 恒等）", pid0, appPid())
        Log.i(TAG, "step1 PARTIAL(B) established: visible={b1,b2}, deck rendered, pid=$pid0")

        // ---- 步骤2：会话内注入 40 填充媒体 → store 47 行；应用可见仍恰={b1,b2}----
        injectFixturesBulk(
            FILLER_NAMES,
            touchTimeOf = { name ->
                val idx = Regex("b2m-fill-(\\d+)\\.png").find(name)?.groupValues?.get(1)?.toInt()
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
        Log.i(TAG, "step2 fillers injected: store=47, app-visible still B (no rescan trigger)")

        // ---- 步骤3：PARTIAL 时代前台刷新重扫（级别不变+foregroundRefresh → revision++）----
        ui.shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(2000)
        ui.launch()
        ui.waitFor(ui.DECK_MARKER, timeoutMs = 30000)
        assertEquals("PARTIAL 时代回前台必须同进程", pid0, appPid())
        awaitCollectionFace(B_NAMES.toSet(), timeoutMs = 15000, tag = "partial-refresh-still-B")
        Log.i(TAG, "step3 partial-era foregroundRefresh rescan done (old-era scan baseline)")

        // ---- 步骤4：会话内授权升级（grant 不杀进程 #52 口径；应用前台持 deck）----
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
        Log.i(TAG, "step4 in-session grant applied, process alive (pid=$pid0), level=FULL")

        // ---- 步骤5：升级重启 ×2（各 revision++；第二次意在提高与在途扫描相撞
        // 的机会——是否真有在途扫描取决于时序，不可确定断言，见类头如实登记）----
        ui.shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(2000)
        ui.launch()
        ui.waitFor(ui.DECK_MARKER, timeoutMs = 30000)
        ui.shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(1000)
        ui.launch()
        ui.waitFor(ui.DECK_MARKER, timeoutMs = 30000)
        assertEquals("升级重启必须同进程（无进程死亡）", pid0, appPid())

        // ---- 断言 a：集合面终态 = 47 名全集（含哨兵 s1 存在性；无重复无缺失）----
        awaitCollectionFace(FULL_EXPECTED_NAMES, timeoutMs = 30000, tag = "full-47")
        val fullFace = visibleDisplayNameList(ctx)
        assertTrue(
            "FULL 集合面不得有重复行（list=${fullFace.size} set=${fullFace.toSet().size}）",
            fullFace.size == fullFace.toSet().size
        )
        Log.i(TAG, "step5a collection face = full 47 names (sentinel s1 included)")

        // ---- 断言 b：deck 存活行同步——升级不白屏不回退（组合观察窗，Phase2 同窗纪律）----
        // 升级重启后 deck 当前批 = PARTIAL 时代批次 {b1,b2} 经存活行同步保持
        // （DeckScreen LaunchedEffect(allImages,...)：存活行替换、失效行剔除、
        // 不回退到门控）。6s 观察窗：应用包窗口有效性 + 门控按钮缺席 + deck 标记
        // 与 B 批次节点在窗（陈旧 NONE 态/门控回退 = 旧态覆盖信号，即败）。
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
        awaitAnyNode(B_NAMES, timeoutMs = 10000, tag = "b2mr-deck-kept-after-upgrade")
        Log.i(TAG, "step5b deck kept alive through upgrade (no gate, no blank-out, window ok)")

        // ---- 断言 c：消费面承接新池（batch-site-4 池集相等 = 终局提交面判别，
        //      动作边界后新事件；瞬态覆盖不可检测如实登记）----
        // 真实 Settings：deck 顶栏"设置"→ Settings（deck 保持组合为 background）→
        // 滚动定位"推荐模式"行 → sheet → 现值"随机漫步"重选（经唯一副标题定位，
        // 行值与 sheet 选项同名歧义规避）→ onRecommendModeChange →
        // recommendModeRefreshKey++ → deck 重取批（batch-site-4，整池）。
        openSettingsFromDeck()
        assertTrue(
            "推荐模式行（滚动定位 + sheet 打开核验）失败",
            ui.tapAnyScrollableToMarker(
                candidates = listOf("推荐模式"),
                verifyMarker = "真正随机抽取",
                tag = "b2mr-recommend-row",
                maxScrolls = 4
            )
        )
        // sheet 选项：副标题唯一（Settings 行 value 同名"随机漫步"歧义规避）；
        // 经 clickNode 祖先回退点击 OptionItem 行（onClick 无条件回调 → 通道触发）。
        // 动作边界标记紧贴触发动作之前写入（程序顺序+缓冲 append 序 ⇒ 之后出现
        // 的批次行必为本次动作产生的新事件）。
        val option = ui.waitFor("真正随机抽取")
        Log.i(TAG, "$REFETCH_BOUNDARY_MARKER before recommend-mode reselect tap")
        ui.clickNode(option, "b2mr-option-random-walk")
        FullPartialFullUi.awaitDismissed("真正随机抽取", tag = "b2mr-sheet-dismissed", timeoutMs = 10000)

        // 池集相等：批次日志行 id 与 47 行 store 全集集合相等（截止轮询 30s）。
        // 判别语义（终局提交面）：最后一次生效的提交池必须为 FULL 全池——若
        // PARTIAL 时代陈旧快照是最后一次提交，重取批只含 2 行 id（红灯）；
        // 瞬态覆盖后被新扫描纠正的形态不可由终态断言检测（如实登记）。批次
        // 事件只认 REFETCH_BOUNDARY_MARKER 之后的新事件：动作后无新批次事件
        // （仅有动作前正确日志，含上一 probe 段遗留行）一律红灯。
        val expectedPoolIds = FULL_EXPECTED_NAMES.map { name ->
            storeAfterFill.getValue(name)
        }.toSet()
        val deadline = SystemClock.uptimeMillis() + 30000
        var lastLine = ""
        var batchIds: Set<String> = emptySet()
        while (SystemClock.uptimeMillis() < deadline) {
            lastLine = latestBatchLogLineAfterBoundary() ?: ""
            batchIds = parseBatchIds(lastLine)
            if (batchIds == expectedPoolIds) break
            SystemClock.sleep(300)
        }
        if (batchIds != expectedPoolIds) {
            ui.dumpTree("timeout-b2mr-pool")
            ui.saveScreenshot("timeout-b2mr-pool")
            error(
                "动作边界后批次日志池集相等失败（消费面未在本次动作后承接 FULL 全池；" +
                    "含动作后无新批次事件/仅有动作前日志的情形）：\n" +
                    "lastLine=$lastLine\nbatchIds(${batchIds.size})=$batchIds\n" +
                    "expectedIds(${expectedPoolIds.size})"
            )
        }
        Log.i(TAG, "step5c post-boundary batch-site-4 pool equality: ${batchIds.size} ids == full 47 pool")

        // ---- 返回 deck：BACK（SETTINGS→DECK 生产回退）+ 全池批次节点可见 ----
        ui.shell("input keyevent KEYCODE_BACK")
        ui.waitFor(ui.DECK_MARKER, timeoutMs = 30000)
        awaitAnyNode(NAMED_NAMES + FILLER_NAMES, timeoutMs = 15000, tag = "b2mr-deck-full-pool")
        assertEquals("全程必须同进程（无进程死亡/无隐性重启）", pid0, appPid())
        Log.i(TAG, "step5 deck consuming full pool, same process (pid=$pid0)")

        // ---- 收尾核对：哨兵 s1 行在库（集合面已含；shell 面双面核对 FPF 同口径）----
        val finalRows = shellStoreRows()
        assertTrue("哨兵 s1 必须在库（shell 面）", SENTINEL_NAME in finalRows)
        assertEquals("终态 store 必须仍为 47 行（应用动作不得增删媒体行）", FULL_EXPECTED_NAMES, finalRows.keys)

        // ---- 用例末自清（目录整体删除 + 逐项核对；@After 另有幂等兜底）----
        val residual = cleanupAllFixtures()
        assertTrue("用例末夹具必须全部删除：residual=$residual", residual.isEmpty())
        Log.i(TAG, "complete: PARTIAL(B)→FULL in-session upgrade wired; post-boundary batch event = full 47 pool committed and consumed (final-state wiring; strict stale-race not constructed here, registered)")
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
     * 残留兜底（幂等）：用例任何路径退出后按目录整体删除夹具行与文件，防死行
     * 残留污染同宿主后续 probe 段（b2pr dependency-skip 按名兜底同款纪律）。
     */
    @After fun cleanup() {
        val residual = cleanupAllFixtures()
        Log.i(TAG, "after cleanup residual=$residual")
    }
}
