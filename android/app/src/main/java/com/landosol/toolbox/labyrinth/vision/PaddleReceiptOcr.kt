package com.landosol.toolbox.labyrinth.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/** assets 下存放识别模型与字典的目录。 */
private const val PPOCR_ASSET_DIR = "ppocr"

/** 一套识别模型：写进报告的名字 + 模型文件名 + 字典文件名 + 可选的 det 文件名。 */
private data class PaddleRecVariant(
    val name: String,
    val model: String,
    val dict: String,
    val det: String?,
)

/**
 * 候选版本按优先级排列。两套都放在 assets 里时优先 v4——它的中文与 `金币`/`×` 明显优于
 * ML Kit，先用它把前后处理对齐；要换 v5 就删掉 v4 的两个文件，或者调整这里的顺序。
 */
private val PPOCR_REC_VARIANTS = listOf(
    PaddleRecVariant("ppocr_v4_mobile_rec", "v4_rec.onnx", "ppocr_keys_v1.txt", "v4_det.onnx"),
    PaddleRecVariant("ppocr_v5_mobile_rec", "v5_rec.onnx", "v5_keys.txt", null),
)

/**
 * 取第一个模型与字典都齐的版本；都不齐时退回首选，让引擎如实失败而不是静默降级。
 *
 * det 是可选项：只有 rec 也能做字段级识别，所以 det 缺失不影响选版本，由
 * [PaddleTextDetector] 自己判断能不能整屏识别。
 */
private fun resolveRecVariant(context: Context): PaddleRecVariant {
    val available = runCatching { context.assets.list(PPOCR_ASSET_DIR)?.toSet() }.getOrNull().orEmpty()
    return PPOCR_REC_VARIANTS
        .firstOrNull { it.model in available && it.dict in available }
        ?.let { variant ->
            if (variant.det != null && variant.det !in available) variant.copy(det = null) else variant
        }
        ?: PPOCR_REC_VARIANTS.first()
}

/** 一条 rec 读数：文本 + 置信度。置信度口径同官方 CTCLabelDecode（保留时间步的最大概率均值）。 */
internal data class PaddleRecLine(val text: String, val score: Float)

/**
 * 回执字段的主读：PaddleOCR mobile rec，跑在 ONNX Runtime 上。
 *
 * 六块字段各自裁剪后逐块识别，读数直接进判定，同时写进调试状态。任何失败都返回 null，
 * 该帧按未读出处理。模型与字典随 assets 分发，首次使用时解到 filesDir；用 v4 还是 v5 由
 * assets 里实际存在的文件决定，见 [PaddleRecVariant] 与 [resolveRecVariant]。
 */
internal class PaddleReceiptOcr(context: Context) {
    private companion object { const val TAG = "PaddleReceiptOcr" }

    // 仪器测试拿到的 context 可能没有 applicationContext，退回传入的 context。
    private val appContext = context.applicationContext ?: context

    // 版本由 assets 里实际存在的文件决定，不是编译期常量：换模型只需换文件，报告里的名字跟着变，
    // 不会出现"报告写 v5、实际跑 v4"这种只能靠 mtime 反推的情况。
    private val variant: PaddleRecVariant = resolveRecVariant(appContext)

    /** 写进调试状态，报告里能看出这帧的读数出自哪个模型。 */
    val modelName: String = variant.name

    /** 与当前 rec 配套的 det 文件名；null 表示这套模型没有 det，整屏识别不可用。 */
    val detFile: String? = variant.det

    private val lock = Any()
    private var engine: Engine? = null
    private var unavailable = false

    /** 同步识别一张裁图；模型不可用或推理失败时返回 null。调用方负责放到后台线程。 */
    fun recognize(bitmap: Bitmap): String? = recognizeLine(bitmap)?.text

    /** 同 [recognize]，但带置信度；整屏 det 链路需要它来过滤低质量行。 */
    fun recognizeLine(bitmap: Bitmap): PaddleRecLine? {
        val current = synchronized(lock) {
            if (unavailable) return null
            engine ?: runCatching { Engine.open(appContext, variant) }
                .onFailure {
                    unavailable = true
                    Log.w(TAG, "model unavailable", it)
                }
                .getOrNull()
                ?.also { engine = it }
        } ?: return null
        return runCatching { current.recognize(bitmap) }
            .onFailure { Log.w(TAG, "recognize failed", it) }
            .getOrNull()
    }

    private class Engine(
        private val env: OrtEnvironment,
        private val session: OrtSession,
        private val chars: List<String>,
        private val inputName: String,
    ) {
        fun recognize(bitmap: Bitmap): PaddleRecLine {
            val prepared = tighten(bitmap)
            val targetHeight = INPUT_HEIGHT
            val targetWidth = ((prepared.width.toFloat() / prepared.height) * targetHeight)
                .toInt().coerceIn(16, 1024)
            val scaled = Bitmap.createScaledBitmap(prepared, targetWidth, targetHeight, true)
            val pixels = IntArray(targetWidth * targetHeight)
            scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            val plane = targetHeight * targetWidth
            val data = FloatArray(3 * plane)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                // PP-OCR 用 OpenCV(BGR) 训练，通道顺序按 BGR 送入。
                val red = (pixel ushr 16 and 0xff) / 255f
                val green = (pixel ushr 8 and 0xff) / 255f
                val blue = (pixel and 0xff) / 255f
                data[index] = (blue - 0.5f) / 0.5f
                data[plane + index] = (green - 0.5f) / 0.5f
                data[2 * plane + index] = (red - 0.5f) / 0.5f
            }
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data),
                longArrayOf(1, 3, targetHeight.toLong(), targetWidth.toLong()))
            val builder = StringBuilder()
            var scoreSum = 0f
            var scoreCount = 0
            try {
                session.run(mapOf(inputName to tensor)).use { output ->
                    val steps = (output[0].value as Array<*>)[0] as Array<FloatArray>
                    var previous = -1
                    for (step in steps) {
                        var best = 0
                        for (index in 1 until step.size) if (step[index] > step[best]) best = index
                        if (best != 0 && best != previous) {
                            val index = best - 1
                            if (index < chars.size) builder.append(chars[index])
                            else if (index == chars.size) builder.append(' ')
                            // 官方 CTCLabelDecode：置信度取保留时间步的最大概率均值。
                            scoreSum += step[best]
                            scoreCount++
                        }
                        previous = best
                    }
                }
            } finally {
                tensor.close()
            }
            return PaddleRecLine(builder.toString(), if (scoreCount == 0) 0f else scoreSum / scoreCount)
        }

        /** 裁图四周常带大片留白，先取墨迹包围盒再补白边，数字才不会被读丢。 */
        private fun tighten(bitmap: Bitmap): Bitmap {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            for (y in 0 until height) for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val luminance = (((pixel ushr 16 and 0xff) * 299 + (pixel ushr 8 and 0xff) * 587 +
                    (pixel and 0xff) * 114) / 1000)
                if (luminance < INK_LUMINANCE) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
            val ink = if (maxX < 0) Rect(0, 0, width, height)
                else Rect(minX, minY, maxX + 1, maxY + 1)
            val padded = Bitmap.createBitmap(ink.width() + PAD * 2, ink.height() + PAD * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(padded)
            canvas.drawColor(Color.WHITE)
            val trimmed = Bitmap.createBitmap(bitmap, ink.left, ink.top, ink.width(), ink.height())
            canvas.drawBitmap(trimmed, PAD.toFloat(), PAD.toFloat(), null)
            return padded
        }

        companion object {
            private const val INPUT_HEIGHT = 48

            /**
             * 取墨迹包围盒的亮度阈值：亮度低于它才算墨迹。
             *
             * 150 对蓝色数字太严——余额行是蓝字渐变，顶部一行被当成背景切掉，`0` 开口就读成 `U`
             * （原生 1920×1080 回执实测：`3,000`→`3,0UU`、`1,830`→`1,83U`、`1,050`→`1,U5U`；
             * 阈值 150 只框到 20 px 高，真实字高 25 px）。
             *
             * 180 与 200 都能让三组读数全部正确，且六个字段零回归（商品名前多余的 `“` 也一并消失）；
             * 220 会把背景渐变也算成墨迹，框高撑到 32 px，读数全乱。取 180 是为了离两侧边界都远一些。
             */
            private const val INK_LUMINANCE = 180
            private const val PAD = 6

            fun open(context: Context, variant: PaddleRecVariant): Engine {
                val model = materialize(context, variant.model)
                val dict = materialize(context, variant.dict)
                val env = OrtEnvironment.getEnvironment()
                val session = env.createSession(model.absolutePath, OrtSession.SessionOptions())
                val chars = dict.readLines().filter { it.isNotEmpty() }
                return Engine(env, session, chars, session.inputNames.first())
            }

            private fun materialize(context: Context, name: String): File {
                val directory = File(context.filesDir, PPOCR_ASSET_DIR).apply { mkdirs() }
                val target = File(directory, name)
                if (!target.exists()) {
                    context.assets.open(PPOCR_ASSET_DIR + "/" + name).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                return target
            }
        }
    }
}
