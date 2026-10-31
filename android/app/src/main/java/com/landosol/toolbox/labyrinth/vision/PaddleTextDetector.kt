package com.landosol.toolbox.labyrinth.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** 单张图最多输出多少条，防止异常帧把内存打满。 */
private const val PPOCR_MAX_LINES = 64

/**
 * 低于这个置信度的行按噪声丢弃。
 *
 * 实测 1920×1080 商店帧：正常文字都在 0.93 以上，而 UI 边缘的碎片（单个 `0`、`笛`、
 * `当前：6）`）落在 0.49~0.91。0.85 能切掉这些碎片又不碰正文。
 */
internal const val PPOCR_MIN_LINE_SCORE = 0.85f

/** 整屏 OCR 的一条结果：四点框（原图坐标，顺序 [左上, 右上, 右下, 左下]）+ 文本 + 置信度。 */
internal data class PaddleOcrLine(
    val box: List<DetPoint>,
    val text: String,
    val score: Float,
)

/**
 * PP-OCRv4 det + rec 的整屏识别，跑在 ONNX Runtime 上。
 *
 * det 侧逐条对照官方 `deploy/cpp_infer`（release/2.7）：
 * - 预处理 `ResizeImgType0`（limit_type=max, limit_side_len=960，边长 round 到 32）
 * - 归一化 `Normalize`（scale=1/255，mean/std 按 **BGR** 顺序逐通道，与官方一致）
 * - 后处理 `DBPostProcessor::BoxesFromBitmap` + `FilterTagDetRes`，几何部分见 [PaddleDbGeometry]
 * - 裁图 `get_rotate_crop_image`（四点透视，高宽比 ≥1.5 转 90°）
 *
 * 与官方的已知差异（显式取舍，不藏在代码里）：
 * 1. `cv::findContours` 用连通域 + 边界像素近似，只取外轮廓，不返回孔洞轮廓；
 * 2. `UnClip` 用矩形外扩替代 ClipperLib 多边形外扩（对矩形等价，官方先取整带来 1 px 内差异）；
 * 3. `BoxScoreFast` 用像素中心偶奇判定替代 `cv::fillPoly` 光栅化。
 */
internal class PaddleTextDetector(context: Context) {

    private companion object {
        const val TAG = "PaddleTextDetector"
    }

    private val appContext = context.applicationContext ?: context
    private val rec = PaddleReceiptOcr(context)
    private val lock = Any()
    private var engine: Engine? = null
    private var unavailable = false

    /** 当前实际使用的模型组合，写进调试状态。 */
    val modelName: String get() = "${rec.detFile ?: "无 det"} + ${rec.modelName}"

    /** 生效的行置信度下限，写进调试状态，便于解释某帧为什么少了几行。 */
    val minLineScore: Float get() = PPOCR_MIN_LINE_SCORE

    /** det 模型缺失或推理失败时返回 null，与"这一帧没有文字"（空列表）区分开。 */
    fun detect(bitmap: Bitmap): List<PaddleOcrLine>? {
        // det 属于同一套模型版本，由 PaddleReceiptOcr 的 variant 统一决定；缺失时整屏识别不可用。
        val detFile = rec.detFile ?: return null
        val current = synchronized(lock) {
            if (unavailable) return null
            engine ?: runCatching { Engine.open(appContext, rec, detFile) }
                .onFailure {
                    unavailable = true
                    Log.w(TAG, "det model unavailable", it)
                }
                .getOrNull()
                ?.also { engine = it }
        } ?: return null
        return runCatching { current.detect(bitmap) }
            .onFailure { Log.w(TAG, "detect failed", it) }
            .getOrNull()
    }

    private class Engine(
        private val env: OrtEnvironment,
        private val session: OrtSession,
        private val inputName: String,
        private val rec: PaddleReceiptOcr,
    ) {

        fun detect(bitmap: Bitmap): List<PaddleOcrLine> {
            val input = Preprocessed.of(bitmap)
            val prob = run(input) ?: return emptyList()
            val boxes = boxesFromBitmap(
                prob, input.width, input.height, input.ratioW, input.ratioH,
                bitmap.width, bitmap.height,
            )
            val lines = ArrayList<PaddleOcrLine>(boxes.size)
            for (box in boxes) {
                if (lines.size >= PPOCR_MAX_LINES) break
                val crop = crop(bitmap, box) ?: continue
                val read = rec.recognizeLine(crop)
                if (!crop.isRecycled) crop.recycle()
                if (read == null || read.text.isBlank()) continue
                if (read.score < PPOCR_MIN_LINE_SCORE) continue
                lines.add(PaddleOcrLine(box, read.text, read.score))
            }
            return lines
        }

        /** 跑一次 det，返回 [0,1] 的概率图（行优先，长度 h*w）。 */
        private fun run(input: Preprocessed): FloatArray? {
            val plane = input.height * input.width
            val tensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(input.data),
                longArrayOf(1, 3, input.height.toLong(), input.width.toLong()),
            )
            return try {
                session.run(mapOf(inputName to tensor)).use { output ->
                    val map = (output[0] as OnnxTensor).floatBuffer
                    val prob = FloatArray(plane)
                    map.get(prob)
                    prob
                }
            } finally {
                tensor.close()
            }
        }

        /**
         * 逐条对照 `BoxesFromBitmap` + `FilterTagDetRes`。
         *
         * 概率图与预处理图同尺寸，所以官方从 bitmap 坐标到 pred 坐标的那次缩放是恒等映射，这里省掉。
         */
        private fun boxesFromBitmap(
            prob: FloatArray,
            width: Int,
            height: Int,
            ratioW: Float,
            ratioH: Float,
            imageWidth: Int,
            imageHeight: Int,
        ): List<List<DetPoint>> {
            val mask = binarize(prob, width, height, PaddleDbParams.DET_DB_THRESH)
            if (PaddleDbParams.DET_DB_USE_DILATION) dilate2x2(mask, width, height)
            val components = connectedComponents(mask, width, height)
            val kept = ArrayList<List<DetPoint>>()
            for (component in components) {
                if (kept.size >= PaddleDbParams.MAX_CANDIDATES) break
                if (component.size <= 2) continue
                val rect = PaddleDbGeometry.minAreaRect(component) ?: continue
                val ordered = PaddleDbGeometry.orderMiniBox(rect.corners)
                if (rect.ssid < PaddleDbParams.MIN_SIZE) continue
                val score = PaddleDbGeometry.boxScoreFast(ordered, prob, width, height)
                if (score < PaddleDbParams.DET_DB_BOX_THRESH) continue
                val unclipped = PaddleDbGeometry.unclipBox(ordered, PaddleDbParams.DET_DB_UNCLIP_RATIO)
                if (unclipped.corners.size != 4) continue
                if (unclipped.ssid < PaddleDbParams.MIN_SIZE + 2) continue
                kept.add(unclipped.corners)
            }
            // FilterTagDetRes
            val root = ArrayList<List<DetPoint>>(kept.size)
            for (box in kept) {
                val clockwise = PaddleDbGeometry.orderPointsClockwise(box)
                val mapped = clockwise.map {
                    DetPoint(
                        (it.x / ratioW).coerceIn(0f, (imageWidth - 1).toFloat()),
                        (it.y / ratioH).coerceIn(0f, (imageHeight - 1).toFloat()),
                    )
                }
                val boxWidth = PaddleDbGeometry.distance(mapped[0], mapped[1]).toInt()
                val boxHeight = PaddleDbGeometry.distance(mapped[0], mapped[3]).toInt()
                if (boxWidth <= 4 || boxHeight <= 4) continue
                root.add(mapped)
            }
            return root.sortedWith(compareBy({ it[0].x }, { it[0].y }))
        }

        private fun binarize(prob: FloatArray, width: Int, height: Int, threshold: Float): ByteArray {
            val mask = ByteArray(width * height)
            for (i in mask.indices) if (prob[i] > threshold) mask[i] = 1
            return mask
        }

        /** cv::dilate 2×2（锚点 (1,1)）：out(y,x) = max(in(y-1..y, x-1..x))。 */
        private fun dilate2x2(mask: ByteArray, width: Int, height: Int) {
            val source = mask.copyOf()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var value: Byte = 0
                    for (dy in -1..0) {
                        val yy = y + dy
                        if (yy < 0 || yy >= height) continue
                        for (dx in -1..0) {
                            val xx = x + dx
                            if (xx < 0 || xx >= width) continue
                            val candidate = source[yy * width + xx]
                            if (candidate > value) value = candidate
                        }
                    }
                    mask[y * width + x] = value
                }
            }
        }

        /**
         * 连通域 + 边界像素，替代 `cv::findContours(RETR_LIST, CHAIN_APPROX_SIMPLE)`。
         *
         * 只取外轮廓：孔洞轮廓在 DB 输出里通常是字内空白，尺寸过小会被 min_size 与
         * box_thresh 两道门槛过滤掉。
         */
        private fun connectedComponents(mask: ByteArray, width: Int, height: Int): List<List<DetPoint>> {
            val visited = BooleanArray(mask.size)
            val stack = IntArray(mask.size)
            val result = ArrayList<List<DetPoint>>()
            for (start in mask.indices) {
                if (mask[start].toInt() == 0 || visited[start]) continue
                var top = 0
                stack[top++] = start
                visited[start] = true
                val boundary = ArrayList<DetPoint>()
                while (top > 0) {
                    val index = stack[--top]
                    val x = index % width
                    val y = index / width
                    var onBoundary = false
                    for (dx in -1..1) {
                        for (dy in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val xx = x + dx
                            val yy = y + dy
                            if (xx < 0 || xx >= width || yy < 0 || yy >= height) {
                                onBoundary = true
                                continue
                            }
                            val next = yy * width + xx
                            if (mask[next].toInt() == 0) {
                                onBoundary = true
                            } else if (!visited[next]) {
                                visited[next] = true
                                stack[top++] = next
                            }
                        }
                    }
                    if (onBoundary) boundary.add(DetPoint(x.toFloat(), y.toFloat()))
                }
                if (boundary.isNotEmpty()) result.add(boundary)
            }
            return result
        }

        /** 官方 `get_rotate_crop_image`：四点透视裁图，高宽比 ≥1.5 时转 90°。 */
        private fun crop(source: Bitmap, box: List<DetPoint>): Bitmap? {
            if (box.size != 4) return null
            val (width, height) = PaddleDbGeometry.cropSize(box)
            if (width <= 0 || height <= 0) return null
            val matrix = Matrix()
            val ok = matrix.setPolyToPoly(
                PaddleDbGeometry.flatten(box), 0,
                PaddleDbGeometry.rectCorners(width, height), 0, 4,
            )
            if (!ok) return null
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            if (!PaddleDbGeometry.needsRotation(width, height)) return out
            val rotated =
                Bitmap.createBitmap(out, 0, 0, out.width, out.height, Matrix().apply { postRotate(90f) }, true)
            if (rotated !== out) out.recycle()
            return rotated
        }

        companion object {
            fun open(context: Context, rec: PaddleReceiptOcr, detFile: String): Engine {
                val file = materialize(context, detFile)
                val env = OrtEnvironment.getEnvironment()
                val session = env.createSession(file.absolutePath, OrtSession.SessionOptions())
                return Engine(env, session, session.inputNames.first(), rec)
            }

            private fun materialize(context: Context, name: String): File {
                val directory = File(context.filesDir, "ppocr").apply { mkdirs() }
                val target = File(directory, name)
                if (!target.exists()) {
                    context.assets.open("ppocr/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                return target
            }
        }
    }

    /** 预处理结果：CHW 浮点数据 + 缩放后尺寸 + 两个方向的缩放比。 */
    private class Preprocessed(
        val data: FloatArray,
        val width: Int,
        val height: Int,
        val ratioW: Float,
        val ratioH: Float,
    ) {
        companion object {
            /** 官方 Normalize 的 mean/std，按 BGR 顺序逐通道使用。 */
            private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
            private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

            /** 对照 `ResizeImgType0`（limit_type=max）+ `Normalize`（is_scale=true）。 */
            fun of(bitmap: Bitmap): Preprocessed {
                val sourceW = bitmap.width
                val sourceH = bitmap.height
                val limit = PaddleDbParams.DET_LIMIT_SIDE_LEN
                val maxSide = max(sourceW, sourceH)
                val ratio = if (maxSide > limit) limit.toFloat() / maxSide else 1f
                var targetH = (sourceH * ratio).toInt()
                var targetW = (sourceW * ratio).toInt()
                targetH = max((targetH / 32f).roundToInt() * 32, 32)
                targetW = max((targetW / 32f).roundToInt() * 32, 32)
                val scaled = if (targetW == sourceW && targetH == sourceH) bitmap
                    else Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                val pixels = IntArray(targetW * targetH)
                scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
                if (scaled !== bitmap) scaled.recycle()
                val plane = targetW * targetH
                val data = FloatArray(3 * plane)
                for (index in pixels.indices) {
                    val pixel = pixels[index]
                    // 官方 cv::split 得到的是 BGR 顺序，mean/std 也按这个顺序逐通道套用。
                    val b = (pixel and 0xff) / 255f
                    val g = (pixel ushr 8 and 0xff) / 255f
                    val r = (pixel ushr 16 and 0xff) / 255f
                    data[index] = (b - MEAN[0]) / STD[0]
                    data[plane + index] = (g - MEAN[1]) / STD[1]
                    data[2 * plane + index] = (r - MEAN[2]) / STD[2]
                }
                return Preprocessed(
                    data = data,
                    width = targetW,
                    height = targetH,
                    ratioW = targetW.toFloat() / sourceW,
                    ratioH = targetH.toFloat() / sourceH,
                )
            }
        }
    }
}
