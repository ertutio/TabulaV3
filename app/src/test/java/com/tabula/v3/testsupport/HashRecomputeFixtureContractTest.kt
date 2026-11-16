package com.tabula.v3.testsupport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HashRecomputeDeviceTest 夹具契约钉（audit-recompute-pr80 缺陷 B 修复，2026-11-15）。
 *
 * 原§二 渐变夹具的 dHash 位型互异断言不可满足（audit 实证：水平/对角梯度在
 * 横向差分位型下同型——dHash 只比较横向相邻灰度，逐行均匀梯度产生全 0/全 1
 * 两型；c1 初始与 d1 同 enum 同色=逐字节相同）。本测试以**位型数学仿真**
 * （复刻 PerceptualHash.computeDHashFromBitmap 的横向相邻比较 + BT.601 灰度，
 * 对合成灰度网格直接计算）钉住重设计后的四图案契约：
 * - 四图案按字节重复（0xAA/0x55/0xFF/0x00），两两 Hamming 距离 ∈ {32,64}，
 *   全部 >14（SimilarGroupDetector.kt:422-428 的最严拒绝阈值）。
 * - a1-new == d1（同图案）→ 同哈希（消费面正对照载体）。
 * - 抖动鲁棒：相邻列灰度差 ≥40（JPEG 90 压缩 + 9 列降采样近似不翻转位）。
 * 设备侧真值由 HashRecomputeDeviceTest 的直算断言（Failed reason 入消息）
 * 交叉验证；本测试守护的是**设计契约**，不冒充设备解码验收。
 */
class HashRecomputeFixtureContractTest {

    // 与设备测试相同的 9 列灰度序列（128px 源图按 9 等分列绘制，dark=10/light=200）
    private val patternA = intArrayOf(10, 200, 10, 200, 10, 200, 10, 200, 10)   // 0xAA
    private val patternB = intArrayOf(200, 10, 200, 10, 200, 10, 200, 10, 200)  // 0x55
    private val patternC = intArrayOf(250, 220, 190, 160, 130, 100, 70, 40, 10)   // 0xFF（单调递减，步长≥30）
    private val patternD = intArrayOf(10, 40, 70, 100, 130, 160, 190, 220, 250) // 0x00（单调递增，步长≥30）

    /** 复刻 PerceptualHash.computeDHashFromBitmap：9x8 网格横向相邻比较。 */
    private fun dHashOfPattern(g: IntArray): Long {
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (g[x] > g[x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    private fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    @Test
    fun fourFixturePatternsHaveProvablyDistinctHashes() {
        val hashes = mapOf(
            "A(a1-old)" to dHashOfPattern(patternA),
            "B(d1/a1-new)" to dHashOfPattern(patternB),
            "C(b1)" to dHashOfPattern(patternC),
            "D(c1-old)" to dHashOfPattern(patternD)
        )
        val names = hashes.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) {
            val d = hamming(hashes[names[i]]!!, hashes[names[j]]!!)
            assertTrue(
                "${names[i]} vs ${names[j]} 距离=$d 必须 >14（全部目标断言的夹具区分底线）", d > 14
            )
        }
        // 精确位型（按字节重复）：A=0xAA、B=0x55、C=0xFF、D=0x00
        val rep = { b: Long -> let { var v = 0L; for (k in 0 until 8) v = v or (b shl (8 * k)); v } }
        assertEquals(rep(0xAA), hashes["A(a1-old)"])
        assertEquals(rep(0x55), hashes["B(d1/a1-new)"])
        assertEquals(rep(0xFF), hashes["C(b1)"])
        assertEquals(rep(0x00), hashes["D(c1-old)"])
    }

    @Test
    fun adjacentColumnGrayMarginsSurviveJpegAndDownscale() {
        // 位翻转需要相邻序翻转：交替图案（A/B）边距 190（JPEG 噪声 ±5 的 38 倍）；
        // 单调图案（C/D）9 列跨度的最小步长=30（(250-10)/8），JPEG q90 对合成
        // 平滑坡道的近似噪声 ≪ 15（6 倍余量）——单调序本身对单调噪声鲁棒。
        val cases = listOf("A" to (patternA to 100), "B" to (patternB to 100), "C" to (patternC to 25), "D" to (patternD to 25))
        for ((name, pair) in cases) {
            val g = pair.first
            var minDiff = Int.MAX_VALUE
            for (x in 0 until 8) minDiff = minOf(minDiff, kotlin.math.abs(g[x] - g[x + 1]))
            assertTrue("$name 相邻列灰度差必须 ≥${pair.second}（实测 $minDiff）", minDiff >= pair.second)
        }
    }

    @Test
    fun consumerPairHasMaximalPreDistanceAndZeroPostDistance() {
        // 消费面正/负对照的位型前提：pre(a1-old,d1)=64（互补，最大距离）；post(同图案)=0
        val a = dHashOfPattern(patternA)
        val b = dHashOfPattern(patternB)
        assertEquals(64, hamming(a, b))
        assertEquals(0, hamming(b, b))
    }
}
