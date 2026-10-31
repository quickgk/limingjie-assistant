package com.landosol.toolbox.labyrinth.vision

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * DB 后处理的纯几何部分，逐条对照 PaddleOCR `deploy/cpp_infer/src/postprocess_op.cpp`
 * （release/2.7）：
 *
 * - `GetContourArea` → [unclipDistance]
 * - `UnClip` → [unclipBox]（官方用 ClipperLib 多边形外扩后取 minAreaRect；矩形外扩与之等价）
 * - `cv::minAreaRect` → [minAreaRect]（凸包 + 旋转卡壳）
 * - `GetMiniBoxes` → [orderMiniBox]（按 x 排序后再按上下关系重排）
 * - `BoxScoreFast` → [boxScoreFast]
 * - `OrderPointsClockwise` → [orderPointsClockwise]
 *
 * 不依赖 Android，便于在 JVM 单测里覆盖；默认参数见 [PaddleDbParams]。
 */
internal data class DetPoint(val x: Float, val y: Float)

/** 官方 ocr_det.cpp 的默认参数，改这里就等于改后处理行为。 */
internal object PaddleDbParams {
    const val DET_LIMIT_SIDE_LEN = 960
    const val DET_DB_THRESH = 0.3f
    const val DET_DB_BOX_THRESH = 0.6f
    const val DET_DB_UNCLIP_RATIO = 1.5f
    const val DET_DB_USE_DILATION = true
    const val MIN_SIZE = 3
    const val MAX_CANDIDATES = 1000
}

/** 一个最小外接矩形：四个角 + 官方 `RotatedRect.size` 的两个边长。 */
internal data class MiniBox(val corners: List<DetPoint>, val width: Float, val height: Float) {
    val ssid: Float get() = max(width, height)
}

internal object PaddleDbGeometry {

    /** cpp `GetContourArea`：distance = 面积 * unclip_ratio / 周长。 */
    fun unclipDistance(box: List<DetPoint>, unclipRatio: Float): Float {
        require(box.size == 4) { "box 必须是 4 个点" }
        var area = 0f
        var perimeter = 0f
        for (i in 0 until 4) {
            val a = box[i]
            val b = box[(i + 1) % 4]
            area += a.x * b.y - a.y * b.x
            perimeter += hypot(a.x - b.x, a.y - b.y)
        }
        area = abs(area / 2f)
        return if (perimeter <= 0f) 0f else area * unclipRatio / perimeter
    }

    /**
     * cpp `UnClip` 的等价实现。
     *
     * 官方把 4 个顶点截断成整数后交给 ClipperLib 做圆角外扩，再对结果取 minAreaRect。
     * 对矩形而言「多边形外扩 distance」与「两条边长各加 2×distance」等价，所以这里直接放大矩形，
     * 省掉一份 Clipper 移植。差异只来自官方先把顶点取整，量级在 1 px 内。
     */
    fun unclipBox(box: List<DetPoint>, unclipRatio: Float): MiniBox {
        val rect = minAreaRect(box) ?: return MiniBox(emptyList(), 1f, 1f)
        return inflate(rect, unclipDistance(orderMiniBox(box), unclipRatio))
    }

    /** 以中心为原点、沿两条边方向各外扩 [distance]。 */
    fun inflate(rect: MiniBox, distance: Float): MiniBox {
        if (rect.corners.size != 4 || rect.width <= 0f || rect.height <= 0f) return rect
        val cx = rect.corners.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = rect.corners.sumOf { it.y.toDouble() }.toFloat() / 4f
        val ux = (rect.corners[1].x - rect.corners[0].x) / rect.width
        val uy = (rect.corners[1].y - rect.corners[0].y) / rect.width
        val vx = (rect.corners[3].x - rect.corners[0].x) / rect.height
        val vy = (rect.corners[3].y - rect.corners[0].y) / rect.height
        val halfW = rect.width / 2f + distance
        val halfH = rect.height / 2f + distance
        fun corner(su: Float, sv: Float) =
            DetPoint(cx + su * halfW * ux + sv * halfH * vx, cy + su * halfW * uy + sv * halfH * vy)
        return MiniBox(
            corners = listOf(corner(-1f, -1f), corner(1f, -1f), corner(1f, 1f), corner(-1f, 1f)),
            width = rect.width + 2f * distance,
            height = rect.height + 2f * distance,
        )
    }

    /**
     * cv::minAreaRect 的等价实现：先求凸包，再让矩形每条边贴着凸包的一条边旋转，取面积最小者。
     *
     * 返回的四个角按 (minU,minV)、(maxU,minV)、(maxU,maxV)、(minU,maxV) 排列，
     * 因此 [MiniBox.width] 对应 0→1 边、[MiniBox.height] 对应 0→3 边。
     */
    fun minAreaRect(points: List<DetPoint>): MiniBox? {
        val hull = convexHull(points)
        if (hull.size < 3) return null
        var bestArea = Float.MAX_VALUE
        var best: MiniBox? = null
        for (i in hull.indices) {
            val p0 = hull[i]
            val p1 = hull[(i + 1) % hull.size]
            val dx = p1.x - p0.x
            val dy = p1.y - p0.y
            val len = hypot(dx, dy)
            if (len < 1e-6f) continue
            val ux = dx / len
            val uy = dy / len
            val vx = -uy
            val vy = ux
            var minU = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE
            for (p in hull) {
                val u = p.x * ux + p.y * uy
                val v = p.x * vx + p.y * vy
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                best = MiniBox(
                    corners = listOf(
                        DetPoint(minU * ux + minV * vx, minU * uy + minV * vy),
                        DetPoint(maxU * ux + minV * vx, maxU * uy + minV * vy),
                        DetPoint(maxU * ux + maxV * vx, maxU * uy + maxV * vy),
                        DetPoint(minU * ux + maxV * vx, minU * uy + maxV * vy),
                    ),
                    width = maxU - minU,
                    height = maxV - minV,
                )
            }
        }
        return best
    }

    /** Andrew monotone chain；点集去重后按 (x,y) 升序，返回逆时针凸包。 */
    fun convexHull(points: List<DetPoint>): List<DetPoint> {
        if (points.size < 3) return points
        val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted
        val hull = ArrayList<DetPoint>(sorted.size * 2)
        for (p in sorted) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        val lower = hull.size + 1
        for (i in sorted.size - 2 downTo 0) {
            val p = sorted[i]
            while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        hull.removeAt(hull.size - 1)
        return hull
    }

    private fun cross(o: DetPoint, a: DetPoint, b: DetPoint): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /**
     * cpp `GetMiniBoxes`：先按 x 升序，再用两组的上下关系把四点排成
     * [左上, 右上, 右下, 左下]。排序用稳定排序，与 std::sort 的等价键相对次序一致。
     */
    fun orderMiniBox(box: List<DetPoint>): List<DetPoint> {
        require(box.size == 4) { "box 必须是 4 个点" }
        val array = box.sortedWith(compareBy { it.x }).toMutableList()
        val a0 = array[0]
        val a1 = array[1]
        val a2 = array[2]
        val a3 = array[3]
        val idx1: DetPoint
        val idx4: DetPoint
        if (a1.y <= a0.y) {
            idx1 = a1
            idx4 = a0
        } else {
            idx1 = a0
            idx4 = a1
        }
        val idx2: DetPoint
        val idx3: DetPoint
        if (a3.y <= a2.y) {
            idx2 = a3
            idx3 = a2
        } else {
            idx2 = a2
            idx3 = a3
        }
        array[0] = idx1
        array[1] = idx2
        array[2] = idx3
        array[3] = idx4
        return array
    }

    /**
     * cpp `BoxScoreFast`：在框的最小外接矩形范围内按多边形掩膜取概率均值。
     *
     * 官方用 `cv::fillPoly` 光栅化，这里用像素中心做偶奇判定，边界像素可能差一两行，
     * 对 0.6 的阈值判定影响可以忽略。
     */
    fun boxScoreFast(box: List<DetPoint>, prob: FloatArray, width: Int, height: Int): Float {
        if (box.size != 4 || width <= 0 || height <= 0) return 0f
        val xmin = floor(box.minOf { it.x }).toInt().coerceIn(0, width - 1)
        val xmax = ceil(box.maxOf { it.x }).toInt().coerceIn(0, width - 1)
        val ymin = floor(box.minOf { it.y }).toInt().coerceIn(0, height - 1)
        val ymax = ceil(box.maxOf { it.y }).toInt().coerceIn(0, height - 1)
        if (xmax < xmin || ymax < ymin) return 0f
        var sum = 0.0
        var count = 0
        for (y in ymin..ymax) {
            val py = y + 0.5f
            for (x in xmin..xmax) {
                if (!insidePolygon(x + 0.5f, py, box)) continue
                sum += prob[y * width + x]
                count++
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    /** 偶奇规则的点在多边形内判定。 */
    fun insidePolygon(px: Float, py: Float, polygon: List<DetPoint>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].x
            val yi = polygon[i].y
            val xj = polygon[j].x
            val yj = polygon[j].y
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * cpp `OrderPointsClockwise`：按 x 排序后分左右两组，各自按 y 排序，
     * 输出 [左上, 右上, 右下, 左下]。
     */
    fun orderPointsClockwise(box: List<DetPoint>): List<DetPoint> {
        if (box.size != 4) return box
        val sorted = box.sortedWith(compareBy { it.x })
        val left = sorted.take(2).sortedBy { it.y }
        val right = sorted.drop(2).sortedBy { it.y }
        return listOf(left[0], right[0], right[1], left[1])
    }

    /** 两点距离，裁图时算目标宽高用。 */
    fun distance(a: DetPoint, b: DetPoint): Float = hypot(a.x - b.x, a.y - b.y)

    /** 把框裁成水平矩形时的目标尺寸：宽取上下边长较大者，高取左右边长较大者。 */
    fun cropSize(box: List<DetPoint>): Pair<Int, Int> {
        if (box.size != 4) return 0 to 0
        val w = max(distance(box[0], box[1]), distance(box[2], box[3]))
        val h = max(distance(box[0], box[3]), distance(box[1], box[2]))
        return max(1, w.toInt()) to max(1, h.toInt())
    }

    /** 官方 `get_rotate_crop_image`：高宽比 ≥ 1.5 时把裁图转 90°。 */
    fun needsRotation(cropWidth: Int, cropHeight: Int): Boolean =
        cropWidth > 0 && cropHeight.toFloat() / cropWidth.toFloat() >= 1.5f

    /** 把四点摊平成 Matrix.setPolyToPoly 需要的 floatArray。 */
    fun flatten(points: List<DetPoint>): FloatArray {
        val out = FloatArray(points.size * 2)
        points.forEachIndexed { i, p ->
            out[i * 2] = p.x
            out[i * 2 + 1] = p.y
        }
        return out
    }

    /** 与 [flatten] 相反，便于在测试里对比。 */
    fun unflatten(values: FloatArray): List<DetPoint> {
        require(values.size % 2 == 0)
        return (0 until values.size / 2).map { DetPoint(values[it * 2], values[it * 2 + 1]) }
    }

    /** 水平矩形的四个角，作为透视变换的目标点。 */
    fun rectCorners(width: Int, height: Int): FloatArray = floatArrayOf(
        0f, 0f,
        (width - 1).toFloat(), 0f,
        (width - 1).toFloat(), (height - 1).toFloat(),
        0f, (height - 1).toFloat(),
    )
}
