package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage

/** An absent card has a light, nearly uniform interior; dark or textured crops stay unknown. */
internal object LabyrinthShopEmptySlotEvidence {
    fun matches(frame: PixelImage, rect: EntryPixelRect): Boolean {
        if (rect.left < 0 || rect.top < 0 || rect.width < 20 || rect.height < 20 ||
            rect.left + rect.width > frame.width || rect.top + rect.height > frame.height) return false
        var bright = 0
        var count = 0
        var sum = 0.0
        var squares = 0.0
        for (y in rect.top until rect.top + rect.height step maxOf(1, rect.height / 40)) {
            for (x in rect.left until rect.left + rect.width step maxOf(1, rect.width / 50)) {
                val color = frame[x, y]
                val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
                val luma = (r + g + b) / 3.0
                if (minOf(r, g, b) >= 220 && maxOf(r, g, b) - minOf(r, g, b) <= 25) bright++
                sum += luma; squares += luma * luma; count++
            }
        }
        if (count == 0 || bright.toDouble() / count < 0.995) return false
        val mean = sum / count
        return squares / count - mean * mean <= 9.0
    }
}
