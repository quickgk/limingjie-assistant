package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import java.util.concurrent.Executors

/** 商店小区域复用回执 Paddle 模型；每次请求独立推理，失败和低置信度不产生读数。 */
internal class PaddleShopTextOcr(private val engine: PaddleReceiptOcr) {
    private companion object {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "paddle-shop-ocr").apply { isDaemon = true }
        }
    }

    private val dispatch = ShopTextReadDispatch<Bitmap>(executor::execute) { bitmap ->
        engine.recognizeLine(bitmap)?.takeIf { it.score >= PPOCR_MIN_LINE_SCORE && it.text.isNotBlank() }?.text
    }

    /** 位图仍由 ROI 调度器持有，只有本次推理回调完成后才释放。 */
    fun readAsync(bitmap: Bitmap, callback: (String?) -> Unit) = dispatch.submit(bitmap, callback)
}
