package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.abs

/** Samples the scrollable shop interior; fixed headers and footers are excluded. */
class LabyrinthShopViewportPixels private constructor(
    private val width: Int, private val height: Int, private val values: IntArray,
) {
    internal val referenceScale: Double get() = minOf(width / 1920.0, height / 1080.0)

    fun stableWith(other: LabyrinthShopViewportPixels): Boolean {
        if (width != other.width || height != other.height) return false
        val errors = values.indices.filter { index ->
            val y = TOP + index / COLS * STEP
            // Buttons are observed afresh and can dim without moving inventory.
            y !in 365..470 && y !in 745..850
        }.groupBy { index -> (index / COLS / 6) to (index % COLS / PER_COLUMN) }
        // A single changed title/card must not be diluted by the other five unchanged positions.
        return errors.values.all { region ->
            val differences = region.map { abs(values[it] - other.values[it]) }
            differences.average() <= 8 && differences.count { it <= 16 } >= differences.size * 0.90
        }
    }

    /** Positive means upward content translation; every supplied occupied column must agree. */
    fun upwardDisplacementFrom(source: LabyrinthShopViewportPixels, columns: Set<Int> = setOf(0, 1, 2)): Int? {
        if (columns.isEmpty() || columns.any { it !in 0..2 }) return null
        if (width != source.width || height != source.height) return null
        fun error(shift: Int, column: Int): Double {
            var sum = 0L
            var count = 0
            for (row in 0 until ROWS) {
                val oldRow = row + shift
                if (oldRow !in 0 until ROWS) continue
                for (x in column * PER_COLUMN until (column + 1) * PER_COLUMN) {
                    sum += abs(values[row * COLS + x] - source.values[oldRow * COLS + x]); count++
                }
            }
            return if (count == 0) Double.MAX_VALUE else sum.toDouble() / count
        }
        val shifts = columns.sorted().map { column ->
            val zero = error(0, column)
            val best = (-32..32).filter { abs(it) >= 8 }.minByOrNull { error(it, column) } ?: return null
            val aligned = error(best, column)
            var texture = 0L
            var samples = 0
            for (row in 1 until ROWS) for (x in column * PER_COLUMN until (column + 1) * PER_COLUMN) {
                texture += abs(source.values[row * COLS + x] - source.values[(row - 1) * COLS + x]); samples++
            }
            if (texture.toDouble() / samples < 5 || aligned > 14 || aligned >= zero * 0.60 || zero - aligned < 5) return null
            best
        }
        if (shifts.max() - shifts.min() > 2) return null
        return shifts.sorted()[shifts.size / 2] * STEP
    }

    companion object {
        private const val TOP = 210
        private const val STEP = 5
        private const val ROWS = 130
        private const val PER_COLUMN = 18
        private const val COLS = 3 * PER_COLUMN
        fun sample(frame: PixelImage): LabyrinthShopViewportPixels? {
            val rect = ReferenceFitMapper.map(frame.width, frame.height, EntryReferenceSize(1920, 1080),
                EntryReferenceRect(0, 0, 1920, 1080)) ?: return null
            val values = IntArray(COLS * ROWS) { index ->
                val column = index % COLS / PER_COLUMN
                val x = 110 + column * 578 + index % PER_COLUMN * 27
                val y = TOP + index / COLS * STEP
                val px = (rect.left + x * rect.width / 1920.0).toInt().coerceIn(0, frame.width - 1)
                val py = (rect.top + y * rect.height / 1080.0).toInt().coerceIn(0, frame.height - 1)
                val color = frame[px, py]
                ((color ushr 16 and 255) * 299 + (color ushr 8 and 255) * 587 + (color and 255) * 114) / 1000
            }
            return LabyrinthShopViewportPixels(frame.width, frame.height, values)
        }
    }
}

fun labyrinthShopSwipe(width: Int, height: Int, direction: LabyrinthShopScroll): AutomationAction.Swipe? {
    if (width <= height || height <= 0) return null
    fun point(y: Int): ScreenPoint? = ReferenceFitMapper.map(width, height, EntryReferenceSize(1920, 1080),
        EntryReferenceRect(1735, y - 5, 10, 10))?.let { ScreenPoint(it.left + it.width / 2f, it.top + it.height / 2f) }
    val upper = point(320) ?: return null
    val lower = point(790) ?: return null
    return AutomationAction.Swipe(
        start = if (direction == LabyrinthShopScroll.TO_BOTTOM) lower else upper,
        end = if (direction == LabyrinthShopScroll.TO_BOTTOM) upper else lower,
        durationMillis = 450,
        holdMillis = 150,
    )
}
