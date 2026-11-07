package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import android.graphics.Color

/** 蓝底白字的标题：白色字形转黑，其余转白；不改动识别文本。 */
internal fun shoppingWhiteTextCrop(source: Bitmap): Bitmap {
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (index in pixels.indices) {
        val pixel = pixels[index]
        val bright = (pixel ushr 16 and 0xff) > 200 && (pixel ushr 8 and 0xff) > 200 && (pixel and 0xff) > 200
        pixels[index] = if (bright) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
}
