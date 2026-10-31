package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * DB 后处理几何部分的回归测试。
 *
 * 期望值不是随手写的：全部按官方 `postprocess_op.cpp` 的公式手算，见每个用例的注释。
 */
class PaddleDbGeometryTest {

    private fun square(size: Float, cx: Float = 0f, cy: Float = 0f): List<DetPoint> {
        val half = size / 2f
        return listOf(
            DetPoint(cx - half, cy - half),
            DetPoint(cx + half, cy - half),
            DetPoint(cx + half, cy + half),
            DetPoint(cx - half, cy + half),
        )
    }

    /** 官方：distance = 面积 * unclip_ratio / 周长。100×100 的框、ratio 1.5 → 10000*1.5/400 = 37.5。 */
    @Test
    fun unclipDistance_matchesOfficialFormula() {
        val distance = PaddleDbGeometry.unclipDistance(square(100f), 1.5f)
        assertEquals(37.5f, distance, 1e-3f)
    }

    /** 边长 10 的框外扩 1，两条边各加 2，宽高从 10 变 12。 */
    @Test
    fun inflate_growsBothSides() {
        val rect = PaddleDbGeometry.minAreaRect(square(10f))
        assertNotNull(rect)
        val grown = PaddleDbGeometry.inflate(rect!!, 1f)
        assertEquals(12f, grown.width, 1e-3f)
        assertEquals(12f, grown.height, 1e-3f)
    }

    /** 未旋转的正方形，minAreaRect 应还原出 10×10，且面积不受旋转搜索影响。 */
    @Test
    fun minAreaRect_axisAlignedSquare() {
        val rect = PaddleDbGeometry.minAreaRect(square(10f, 50f, 60f))
        assertNotNull(rect)
        assertEquals(10f, rect!!.width, 1e-3f)
        assertEquals(10f, rect.height, 1e-3f)
        assertEquals(10f, rect.ssid, 1e-3f)
    }

    /** 旋转 30° 的 20×8 矩形：minAreaRect 应还原出两个边长 20 与 8（顺序不限）。 */
    @Test
    fun minAreaRect_rotatedRectangleRecoversSides() {
        val angle = Math.toRadians(30.0)
        val (w, h) = 20f to 8f
        val corners = listOf(-w / 2, w / 2, w / 2, -w / 2).zip(listOf(-h / 2, -h / 2, h / 2, h / 2))
            .map { (x, y) ->
                DetPoint(
                    (x * cos(angle) - y * sin(angle)).toFloat(),
                    (x * sin(angle) + y * cos(angle)).toFloat(),
                )
            }
        val rect = PaddleDbGeometry.minAreaRect(corners)
        assertNotNull(rect)
        val sides = listOf(rect!!.width, rect.height).sorted()
        assertEquals(8f, sides[0], 1e-2f)
        assertEquals(20f, sides[1], 1e-2f)
    }

    /** 四点乱序输入，orderMiniBox 必须还原成 [左上, 右上, 右下, 左下]。 */
    @Test
    fun orderMiniBox_restoresClockwiseCorners() {
        val shuffled = listOf(
            DetPoint(100f, 100f),
            DetPoint(0f, 0f),
            DetPoint(0f, 100f),
            DetPoint(100f, 0f),
        )
        val ordered = PaddleDbGeometry.orderMiniBox(shuffled)
        assertEquals(DetPoint(0f, 0f), ordered[0])
        assertEquals(DetPoint(100f, 0f), ordered[1])
        assertEquals(DetPoint(100f, 100f), ordered[2])
        assertEquals(DetPoint(0f, 100f), ordered[3])
    }

    /** orderPointsClockwise 与 orderMiniBox 对同一组点的期望一致。 */
    @Test
    fun orderPointsClockwise_matchesMiniBoxOrder() {
        val shuffled = listOf(
            DetPoint(100f, 100f),
            DetPoint(0f, 0f),
            DetPoint(0f, 100f),
            DetPoint(100f, 0f),
        )
        assertEquals(
            PaddleDbGeometry.orderMiniBox(shuffled),
            PaddleDbGeometry.orderPointsClockwise(shuffled),
        )
    }

    /** 概率图全 1 时框内均值必须是 1。 */
    @Test
    fun boxScoreFast_allOnes() {
        val width = 20
        val height = 20
        val prob = FloatArray(width * height) { 1f }
        val score = PaddleDbGeometry.boxScoreFast(square(10f, 10f, 10f), prob, width, height)
        assertEquals(1f, score, 1e-4f)
    }

    /** 概率图左半边 1、右半边 0，左半边的框得分应接近 1，右半边接近 0。 */
    @Test
    fun boxScoreFast_respectsMaskRegion() {
        val width = 40
        val height = 40
        val prob = FloatArray(width * height) { index -> if (index % width < 20) 1f else 0f }
        val left = PaddleDbGeometry.boxScoreFast(square(10f, 10f, 20f), prob, width, height)
        val right = PaddleDbGeometry.boxScoreFast(square(10f, 30f, 20f), prob, width, height)
        assertTrue("左半区得分应接近 1，实际 $left", abs(left - 1f) < 1e-4f)
        assertTrue("右半区得分应接近 0，实际 $right", right < 1e-4f)
    }

    /** 竖直长条（高/宽 ≥ 1.5）要转 90°，水平长条不转。 */
    @Test
    fun needsRotation_threshold() {
        assertTrue(PaddleDbGeometry.needsRotation(20, 30))
        assertTrue(!PaddleDbGeometry.needsRotation(30, 20))
        assertTrue(!PaddleDbGeometry.needsRotation(20, 29))
    }

    /** 裁图目标尺寸取对边长度的较大者，与官方 cropSize 口径一致。 */
    @Test
    fun cropSize_usesLongerOppositeSides() {
        val box = listOf(
            DetPoint(0f, 0f),
            DetPoint(100f, 0f),
            DetPoint(100f, 30f),
            DetPoint(0f, 30f),
        )
        assertEquals(100 to 30, PaddleDbGeometry.cropSize(box))
    }
}
