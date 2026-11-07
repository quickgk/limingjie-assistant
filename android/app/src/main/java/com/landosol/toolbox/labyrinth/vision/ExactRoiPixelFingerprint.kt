package com.landosol.toolbox.labyrinth.vision

/** Row-major exact hash, including the original size seed and Long overflow semantics. */
internal fun exactRoiPixelFingerprint(pixels: IntArray, width: Int, height: Int): Long {
    require(width > 0 && height > 0 && pixels.size == width * height)
    var hash = 1125899906842597L + width * 31L + height
    for (pixel in pixels) hash = hash * 31 + pixel
    return hash
}
