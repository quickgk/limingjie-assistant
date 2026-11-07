package com.landosol.toolbox.labyrinth.vision

/** Physical positions exist only within one stock generation. */
object LabyrinthShopSlots {
    val keys: List<String> = (1..6).map { "shop_item_$it" }
    fun complete(items: List<LabyrinthShopItemMatch>): Boolean =
        items.size == keys.size && items.map { it.slotId }.toSet() == keys.toSet()
}

enum class LabyrinthShopViewport { UNKNOWN, A, B }
enum class LabyrinthShopScanPhase { NEED_A, SCROLL_DOWN_PENDING, WAIT_STABLE_B, COMPLETE }
enum class LabyrinthShopScroll { TO_TOP, TO_BOTTOM }

data class LabyrinthShopCoverage(
    val epoch: Long,
    val capturedAt: Long,
    val phase: LabyrinthShopScanPhase,
    val scroll: LabyrinthShopScroll? = null,
    val mode: LabyrinthShopCoverageMode = LabyrinthShopCoverageMode.FULL,
    val itemSlots: Set<String> = LabyrinthShopSlots.keys.toSet(),
    val remainingCount: Int? = null,
    val absentSlots: Set<String> = emptySet(),
    val soldSlots: Set<String> = emptySet(),
) {
    fun complete(items: List<LabyrinthShopItemMatch>): Boolean =
        phase == LabyrinthShopScanPhase.COMPLETE && items.map { it.slotId }.toSet() == itemSlots &&
            items.size == itemSlots.size && itemSlots.all { it in LabyrinthShopSlots.keys } &&
            absentSlots.all { it in LabyrinthShopSlots.keys } && itemSlots.intersect(absentSlots).isEmpty() &&
            soldSlots.all { it in LabyrinthShopSlots.keys } && itemSlots.intersect(soldSlots).isEmpty() &&
            absentSlots.intersect(soldSlots).isEmpty() && itemSlots + absentSlots + soldSlots == LabyrinthShopSlots.keys.toSet() &&
            (mode == LabyrinthShopCoverageMode.FULL && soldSlots.isEmpty() ||
                mode == LabyrinthShopCoverageMode.SINGLE_SCREEN && remainingCount in 0..3 && remainingCount == items.size) &&
            items.none { it.status == LabyrinthShopItemStatus.UNKNOWN }
}

enum class LabyrinthShopCoverageMode { FULL, SINGLE_SCREEN }

/** Full inventory requires translated A/B views; compact inventory requires two stable real views. */
class LabyrinthShopStockScanner {
    @Volatile var epoch: Long = 0; private set
    private var phase = LabyrinthShopScanPhase.NEED_A
    private var a: LabyrinthShopObservation? = null
    private var b: LabyrinthShopObservation? = null
    private var topReturnSource: LabyrinthShopObservation? = null
    private var lastCapture = Long.MIN_VALUE
    private var dispatchedAt = Long.MIN_VALUE
    private var direction: LabyrinthShopScroll? = null
    private var executed = false
    private var scope: String? = null
    private var captureAfter = Long.MIN_VALUE
    private var expectedRemaining: Int? = null
    private var soldSlots: Set<String> = emptySet()
    private var single: LabyrinthShopObservation? = null
    private var singleAttempts = 0
    private var emptyPositioned = false
    var singleScreenRejected: Boolean = false; private set

    @Synchronized fun purchasedSlots(): Set<String> = soldSlots.toSet()

    @Synchronized fun expectRemaining(count: Int?, purchased: Set<String> = emptySet()) {
        require(count == null || count in 0..3)
        require(purchased.all { it in LabyrinthShopSlots.keys } &&
            (purchased.isEmpty() || count != null && purchased.size == 6 - count))
        if (expectedRemaining != count || soldSlots != purchased) {
            expectedRemaining = count
            soldSlots = purchased.toSet()
            single = null; singleAttempts = 0
            phase = LabyrinthShopScanPhase.NEED_A
            a = null; b = null; direction = null
        }
        singleScreenRejected = false
    }

    @Synchronized fun invalidate(notBefore: Long = Long.MIN_VALUE) {
        epoch++
        captureAfter = notBefore
        phase = LabyrinthShopScanPhase.NEED_A
        a = null; b = null; single = null; emptyPositioned = false; topReturnSource = null; lastCapture = Long.MIN_VALUE
        dispatchedAt = Long.MIN_VALUE; direction = null; executed = false
    }

    @Synchronized fun bindScope(value: String, notBefore: Long = Long.MIN_VALUE) {
        if (scope != value) { invalidate(notBefore); scope = value; expectedRemaining = null; soldSlots = emptySet() }
    }

    @Synchronized fun dispatched(expectedEpoch: Long, scroll: LabyrinthShopScroll, at: Long): Boolean {
        if (expectedEpoch != epoch || direction != null ||
            (scroll == LabyrinthShopScroll.TO_BOTTOM && phase != LabyrinthShopScanPhase.SCROLL_DOWN_PENDING) ||
            (scroll == LabyrinthShopScroll.TO_TOP && phase != LabyrinthShopScanPhase.NEED_A)) return false
        direction = scroll; dispatchedAt = at; executed = false; b = null
        if (scroll == LabyrinthShopScroll.TO_BOTTOM) phase = LabyrinthShopScanPhase.WAIT_STABLE_B
        return true
    }

    @Synchronized fun actionFinished(expectedEpoch: Long, success: Boolean) {
        if (expectedEpoch != epoch || direction == null) return
        if (!success) { invalidate(dispatchedAt); return }
        executed = true
    }

    @Synchronized fun observe(shop: LabyrinthShopObservation, capturedAt: Long): LabyrinthShopObservation {
        fun pending(scroll: LabyrinthShopScroll? = null) = shop.copy(coverage = LabyrinthShopCoverage(epoch, capturedAt, phase, scroll))
        if (shop.stockEpoch != epoch || capturedAt <= captureAfter) {
            // An inadmissible frame cannot bridge two otherwise valid B observations.
            if (phase == LabyrinthShopScanPhase.WAIT_STABLE_B) b = null
            single = null
            return shop.copy(coverage = null)
        }
        if (capturedAt <= lastCapture) return shop.copy(coverage = null)
        lastCapture = capturedAt
        if (shop.dialogState != LabyrinthShopDialogState.NONE || shop.purchaseReceipt != null) {
            invalidate(capturedAt); return shop.copy(coverage = null)
        }
        val pixels = shop.viewportPixels ?: run {
            b = null; single = null
            return shop.copy(coverage = null)
        }
        val allAbsent = shop.emptySlotIds == LabyrinthShopSlots.keys.toSet() &&
            shop.items.none { it.buyButtonScore >= 0.55 }
        if (allAbsent && expectedRemaining != null && expectedRemaining != 0) {
            expectedRemaining = null; soldSlots = emptySet(); singleScreenRejected = true; invalidate(capturedAt)
            return shop.copy(coverage = null)
        }
        if (!allAbsent) emptyPositioned = false
        if (allAbsent && direction == null && !emptyPositioned) {
            phase = LabyrinthShopScanPhase.NEED_A
            topReturnSource = shop
            return pending(LabyrinthShopScroll.TO_TOP)
        }
        if (allAbsent && direction == LabyrinthShopScroll.TO_TOP) {
            if (!executed || capturedAt <= dispatchedAt) return pending()
            emptyPositioned = true
            direction = null; dispatchedAt = Long.MIN_VALUE; topReturnSource = null
            single = null
        }
        if (allAbsent && emptyPositioned) {
            // A completed top-positioning action and two new whole-shelf absence views prove empty stock without a button offset.
            val previous = single
            single = shop
            if (previous == null || previous.emptySlotIds != shop.emptySlotIds ||
                previous.viewportPixels?.stableWith(pixels) != true) return pending()
            phase = LabyrinthShopScanPhase.COMPLETE
            return shop.copy(items = emptyList(), coverage = LabyrinthShopCoverage(epoch, capturedAt, phase,
                mode = if (expectedRemaining == 0) LabyrinthShopCoverageMode.SINGLE_SCREEN else LabyrinthShopCoverageMode.FULL,
                itemSlots = emptySet(), absentSlots = LabyrinthShopSlots.keys.toSet(),
                remainingCount = if (expectedRemaining == 0) 0 else null))
        }
        if (direction == null && expectedRemaining != null && soldSlots.isNotEmpty())
            return observeSingle(shop, capturedAt, pixels)
        if (direction == LabyrinthShopScroll.TO_TOP) {
            if (!executed || capturedAt <= dispatchedAt || shop.viewport != LabyrinthShopViewport.A) return pending()
            val source = topReturnSource ?: return pending()
            if (expectedRemaining != null) {
                // Compact inventory can clamp a top swipe; its real top geometry and two new stable frames prove location.
                direction = null; dispatchedAt = Long.MIN_VALUE; topReturnSource = null
                single = null
            } else {
                val displacement = source.viewportPixels?.let { pixels.upwardDisplacementFrom(it, occupiedColumns(source)) } ?: return pending()
                if (displacement !in -160..-60 || kotlin.math.abs(displacement - (shop.contentOffset - source.contentOffset)) > 15) return pending()
                direction = null; dispatchedAt = Long.MIN_VALUE; topReturnSource = null
            }
        }
        if (expectedRemaining != null) return observeSingle(shop, capturedAt, pixels)
        when (phase) {
            LabyrinthShopScanPhase.NEED_A -> {
                if (shop.viewport == LabyrinthShopViewport.B) {
                    topReturnSource = shop
                    return pending(LabyrinthShopScroll.TO_TOP)
                }
                if (shop.viewport != LabyrinthShopViewport.A || !LabyrinthShopSlots.complete(shop.items) ||
                    shop.items.any { it.categoryState == LabyrinthShopCategoryState.READING }) return pending()
                a = shop
                phase = LabyrinthShopScanPhase.SCROLL_DOWN_PENDING
                return pending(LabyrinthShopScroll.TO_BOTTOM)
            }
            LabyrinthShopScanPhase.SCROLL_DOWN_PENDING -> {
                if (shop.viewport != LabyrinthShopViewport.A || a?.viewportPixels?.stableWith(pixels) != true) {
                    invalidate(capturedAt); return shop.copy(coverage = null)
                }
                a = shop
                return pending(LabyrinthShopScroll.TO_BOTTOM)
            }
            LabyrinthShopScanPhase.WAIT_STABLE_B -> {
                fun unstable(): LabyrinthShopObservation { b = null; return pending() }
                if (!executed || capturedAt <= dispatchedAt || shop.viewport != LabyrinthShopViewport.B) return unstable()
                val original = a ?: return unstable()
                val displacement = original.viewportPixels?.let { pixels.upwardDisplacementFrom(it, occupiedColumns(original)) } ?: return unstable()
                if (displacement !in 60..160 || kotlin.math.abs(displacement - (shop.contentOffset - original.contentOffset)) > 15) return unstable()
                val previous = b
                b = shop
                if (previous == null || !pixels.stableWith(previous.viewportPixels ?: return pending()) || !sameButtons(previous, shop)) return pending()
                if (merge(original, shop) == null) return pending()
                phase = LabyrinthShopScanPhase.COMPLETE
                direction = null
            }
            LabyrinthShopScanPhase.COMPLETE -> {
                val previous = b
                if (shop.viewport != LabyrinthShopViewport.B || previous == null || previous.viewportPixels?.stableWith(pixels) != true ||
                    !LabyrinthShopSlots.complete(shop.items) || !LabyrinthShopSlots.complete(previous.items) ||
                    previous.emptySlotIds != shop.emptySlotIds ||
                    previous.items.filterNot { it.slotId in shop.emptySlotIds }.any { old -> shop.items.single { it.slotId == old.slotId }.let { current ->
                        current.buyButtonRect != old.buyButtonRect || current.status == LabyrinthShopItemStatus.UNKNOWN ||
                            (old.status == LabyrinthShopItemStatus.DISABLED && current.status == LabyrinthShopItemStatus.AVAILABLE)
                    } }) {
                    invalidate(capturedAt); return shop.copy(coverage = null)
                }
            }
        }
        val merged = a?.let { merge(it, shop) } ?: return shop.copy(coverage = null)
        b = shop
        return shop.copy(items = merged, coverage = LabyrinthShopCoverage(epoch, capturedAt, LabyrinthShopScanPhase.COMPLETE,
            itemSlots = merged.mapTo(mutableSetOf()) { it.slotId }, absentSlots = shop.emptySlotIds))
    }

    private fun observeSingle(shop: LabyrinthShopObservation, capturedAt: Long,
        pixels: LabyrinthShopViewportPixels): LabyrinthShopObservation {
        fun pending(scroll: LabyrinthShopScroll? = null) = shop.copy(coverage =
            LabyrinthShopCoverage(epoch, capturedAt, LabyrinthShopScanPhase.NEED_A, scroll))
        if (++singleAttempts > 12) {
            expectedRemaining = null; soldSlots = emptySet(); singleScreenRejected = true; invalidate(capturedAt)
            return shop.copy(coverage = null)
        }
        if (soldSlots.isEmpty() && shop.viewport != LabyrinthShopViewport.A) {
            single = null
            topReturnSource = shop
            return pending(LabyrinthShopScroll.TO_TOP)
        }
        val count = checkNotNull(expectedRemaining)
        val actual = shop.items.filter { it.slotId !in shop.emptySlotIds && it.slotId !in soldSlots }
        val slots = actual.mapTo(mutableSetOf()) { it.slotId }
        val extra = shop.items.any { it.slotId in soldSlots && it.status == LabyrinthShopItemStatus.AVAILABLE } ||
            actual.count { it.status == LabyrinthShopItemStatus.AVAILABLE } > count
        if (extra) {
            expectedRemaining = null; soldSlots = emptySet(); singleScreenRejected = true; invalidate(capturedAt)
            return shop.copy(coverage = null)
        }
        val absenceProved = (LabyrinthShopSlots.keys.toSet() - slots - soldSlots).all { it in shop.emptySlotIds }
        val remaining = actual.sortedWith(compareBy({ it.buyButtonRect.top }, { it.buyButtonRect.left }))
        val scale = pixels.referenceScale
        val geometry = remaining.map { it.buyButtonRect }
        val geometryReady = geometry.all { it.width > 0 && it.height > 0 &&
            it.top >= 202 * scale && it.top + it.height <= 875 * scale } &&
            geometry.indices.all { i -> geometry.drop(i + 1).none { other ->
                val rect = geometry[i]
                rect.left < other.left + other.width && other.left < rect.left + rect.width &&
                    rect.top < other.top + other.height && other.top < rect.top + rect.height
            } }
        if (!absenceProved || !geometryReady || remaining.size != count || remaining.map { it.slotId }.toSet() != slots ||
            remaining.any { it.status == LabyrinthShopItemStatus.UNKNOWN || !identityReady(it) } ||
            remaining.any { it.price == null || it.price!! <= 0 }) {
            single = null
            if ((!geometryReady || remaining.size != count || !absenceProved) &&
                (shop.viewport == LabyrinthShopViewport.B || shop.viewport == LabyrinthShopViewport.UNKNOWN)) {
                topReturnSource = shop
                return pending(LabyrinthShopScroll.TO_TOP)
            }
            return pending()
        }
        val current = shop.copy(items = remaining)
        val previous = single
        single = current
        if (previous == null || previous.items.map { it.slotId }.toSet() != slots ||
            !pixels.stableWith(previous.viewportPixels ?: return pending()) ||
            previous.items.any { old -> remaining.single { it.slotId == old.slotId }.let {
                it.buyButtonRect != old.buyButtonRect || it.status != old.status ||
                    it.relicMatch?.relicId != old.relicMatch?.relicId || it.titleText != old.titleText || it.price != old.price
            } }) return pending()
        phase = LabyrinthShopScanPhase.COMPLETE
        singleAttempts = 0
        return current.copy(coverage = LabyrinthShopCoverage(epoch, capturedAt, phase,
            mode = LabyrinthShopCoverageMode.SINGLE_SCREEN, itemSlots = slots, remainingCount = count,
            absentSlots = shop.emptySlotIds - soldSlots, soldSlots = soldSlots))
    }

    private fun identityReady(item: LabyrinthShopItemMatch): Boolean =
        item.categoryState == LabyrinthShopCategoryState.READY && when (item.kind) {
            LabyrinthShopItemKind.RELIC -> item.relicMatch?.recognized == true
            LabyrinthShopItemKind.ROLE_IMPRINT -> !item.roleImprintLabel.isNullOrBlank() &&
                LabyrinthShopItemText.confirmedImprintChoice(item.titleText) != null
            else -> false
        }

    private fun occupiedColumns(shop: LabyrinthShopObservation): Set<Int> = shop.items
        .filterNot { it.slotId in shop.emptySlotIds }
        .map { LabyrinthShopSlots.keys.indexOf(it.slotId) % 3 }.toSet()

    private fun sameButtons(first: LabyrinthShopObservation, second: LabyrinthShopObservation): Boolean =
        LabyrinthShopSlots.complete(first.items) && LabyrinthShopSlots.complete(second.items) &&
            first.emptySlotIds == second.emptySlotIds &&
            first.items.filterNot { it.slotId in first.emptySlotIds }.all { old -> second.items.single { it.slotId == old.slotId }.let {
                it.buyButtonRect == old.buyButtonRect && it.status == old.status
            } }

    private fun buttonGeometry(shop: LabyrinthShopObservation): List<EntryPixelRect>? {
        if (!LabyrinthShopSlots.complete(shop.items)) return null
        val rects = LabyrinthShopSlots.keys.map { key -> shop.items.single { it.slotId == key }.buyButtonRect }
        if (rects.any { it.width <= 0 || it.height <= 0 }) return null
        for (row in rects.chunked(3)) {
            if (row.maxOf { it.top } - row.minOf { it.top } > 2 ||
                row.zipWithNext().any { (left, right) -> left.left + left.width > right.left }) return null
        }
        for (column in 0..2) {
            val first = rects[column]; val second = rects[column + 3]
            if (first.left != second.left || first.width != second.width ||
                second.top < first.top + first.height) return null
        }
        return rects
    }

    private fun merge(top: LabyrinthShopObservation, bottom: LabyrinthShopObservation): List<LabyrinthShopItemMatch>? {
        val aRects = buttonGeometry(top) ?: return null
        val bRects = buttonGeometry(bottom) ?: return null
        val scale = bottom.viewportPixels?.referenceScale ?: return null
        val shift = (bottom.contentOffset - top.contentOffset) * scale
        if (aRects.zip(bRects).any { (a, b) -> a.left != b.left || a.width != b.width ||
                kotlin.math.abs(a.height - b.height) > 2 || kotlin.math.abs(a.top - b.top - shift) > 2 }) return null
        if (top.emptySlotIds != bottom.emptySlotIds || top.emptySlotIds.any { it !in LabyrinthShopSlots.keys }) return null
        return LabyrinthShopSlots.keys.filterNot { it in bottom.emptySlotIds }.map { key ->
            val identity = top.items.single { it.slotId == key }
            val current = bottom.items.single { it.slotId == key }
            if (current.status == LabyrinthShopItemStatus.UNKNOWN) return null
            val identified = identity.categoryState == LabyrinthShopCategoryState.READY && when (identity.kind) {
                LabyrinthShopItemKind.RELIC -> identity.relicMatch?.recognized == true
                LabyrinthShopItemKind.ROLE_IMPRINT -> !identity.roleImprintLabel.isNullOrBlank() &&
                    LabyrinthShopItemText.confirmedImprintChoice(identity.titleText) != null
                else -> false
            }
            if (current.status != LabyrinthShopItemStatus.DISABLED && !identified) return null
            identity.copy(screenRect = current.screenRect, buyButtonRect = current.buyButtonRect,
                buyButtonScore = current.buyButtonScore, buyButtonEnabledEvidence = current.buyButtonEnabledEvidence,
                status = current.status)
        }
    }
}
