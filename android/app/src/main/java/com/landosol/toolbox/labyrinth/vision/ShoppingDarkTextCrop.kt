package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** 保留完整深灰标题字形，排除蓝色分隔线；不改动识别文字。 */
internal fun shoppingDarkTextCrop(source: Bitmap): Bitmap {
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (index in pixels.indices) pixels[index] = shoppingDarkTextPixel(pixels[index])
    return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
}

internal fun shoppingDarkTextPixel(pixel: Int): Int {
    val red = pixel ushr 16 and 0xff
    val green = pixel ushr 8 and 0xff
    val blue = pixel and 0xff
    val maximum = maxOf(red, green, blue)
    val dark = maximum < 200 && maximum - minOf(red, green, blue) <= 32
    return if (dark) 0xff000000.toInt() else 0xffffffff.toInt()
}
