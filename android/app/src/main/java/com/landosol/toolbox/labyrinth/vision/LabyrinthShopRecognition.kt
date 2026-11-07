package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class LabyrinthShopDialogState {
    NONE,
    PURCHASE_CONFIRMATION,
    PURCHASE_COMPLETE,
    EXIT_CONFIRMATION,
}

internal val LABYRINTH_SHOP_CONFIRMATION_TITLE_RECT = EntryReferenceRect(560, 345, 758, 60)
internal val LABYRINTH_SHOP_CONFIRMATION_BALANCE_RECT = EntryReferenceRect(935, 735, 190, 48)

internal val LABYRINTH_SHOP_GENERIC_TITLE_RECT = EntryReferenceRect(850, 270, 220, 48)
internal val LABYRINTH_SHOP_REFRESH_BALANCE_RECT = EntryReferenceRect(1260, 600, 150, 48)

/** 仅识别当前商店上的实际通用双按钮框，不授予任何动作。 */
internal fun labyrinthShopGenericDialogVisible(result: LabyrinthEntryFrameResult): Boolean =
    result.nodeMoveConfirmation != null && result.shopObservation?.purchaseReceipt == null &&
        result.observation.state in setOf(LabyrinthEntryPageState.SHOP, LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION) &&
        result.observation.anchorScores[EntryAnchorId.SHOP_TITLE] >= 0.65

/** 更新与退出共用蓝底模板；仅当前弹窗标题双读精确匹配后才可交给刷新意图。 */
internal fun labyrinthShopRefreshDialogVisible(result: LabyrinthEntryFrameResult): Boolean =
    labyrinthShopGenericDialogVisible(result) && result.observation.state != LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION &&
        result.shopObservation?.refreshConfirmationTitle?.let {
        it.confirmations >= 2 && it.text?.filterNot(Char::isWhitespace) == "更新确认"
    } == true

internal fun labyrinthShopExitDialogConfirmed(result: LabyrinthEntryFrameResult): Boolean =
    labyrinthShopGenericDialogVisible(result) && result.shopObservation?.refreshConfirmationTitle?.let {
        it.confirmations >= 2 && it.text?.filterNot(Char::isWhitespace) in setOf("商店确认", "退出确认")
    } == true

/** 标题缺读或单读期间只保留已有刷新意图，不能确认退出或消费。 */
internal fun labyrinthShopGenericDialogReading(result: LabyrinthEntryFrameResult): Boolean =
    labyrinthShopGenericDialogVisible(result) && result.shopObservation?.refreshConfirmationTitle.let {
        it == null || it.confirmations < 2 || it.text.isNullOrBlank()
    }

/** 真实OCR覆盖相似模板分类；读取中或矛盾标题保持UNKNOWN，不继承退出确认许可。 */
internal fun resolveShoppingGenericDialog(result: LabyrinthEntryFrameResult,
    read: LabyrinthShopReceiptRead?): LabyrinthEntryFrameResult {
    if (!labyrinthShopGenericDialogVisible(result)) return result
    val exit = read?.let { it.confirmations >= 2 && it.text?.filterNot(Char::isWhitespace) in
        setOf("商店确认", "退出确认") } == true &&
        result.observation.anchorScores[EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE] >= 0.65
    return result.copy(
        observation = result.observation.copy(state = if (exit) LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION
            else LabyrinthEntryPageState.UNKNOWN),
        shopObservation = (result.shopObservation ?: LabyrinthShopObservation(emptyList())).copy(
            dialogState = if (exit) LabyrinthShopDialogState.EXIT_CONFIRMATION else LabyrinthShopDialogState.NONE,
            refreshConfirmationTitle = read),
    )
}

internal fun labyrinthCurrencyReadSource(result: LabyrinthEntryFrameResult): LabyrinthCurrencySource? {
    if (result.shopObservation?.purchaseReceipt != null) return null
    if (result.observation.state == LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION)
        return if (result.shopObservation?.dialogState == LabyrinthShopDialogState.PURCHASE_CONFIRMATION)
            LabyrinthCurrencySource.PURCHASE_CONFIRMATION else null
    if (result.nodeMoveConfirmation != null) return if (labyrinthShopRefreshDialogVisible(result))
        LabyrinthCurrencySource.REFRESH_CONFIRMATION else null
    return when (result.observation.state) {
        LabyrinthEntryPageState.SHOP, LabyrinthEntryPageState.NODE_SELECTION -> LabyrinthCurrencySource.WALLET
        else -> null
    }
}

enum class LabyrinthShopItemStatus {
    AVAILABLE,
    DISABLED,
    UNKNOWN,
}

enum class LabyrinthShopItemKind {
    UNKNOWN,
    RELIC,
    ROLE_IMPRINT,
}

enum class LabyrinthShopCategoryState { READY, READING, EXHAUSTED }

data class LabyrinthShopItemMatch(
    val slotId: String,
    val screenRect: EntryPixelRect,
    val buyButtonRect: EntryPixelRect,
    val buyButtonScore: Double,
    val buyButtonEnabledEvidence: Double = 0.0,
    val status: LabyrinthShopItemStatus,
    val relicMatch: LabyrinthRelicMatch? = null,
    /** Semantic title classification. Android OCR fills ROLE_IMPRINT for "XX职能...印记" goods. */
    val kind: LabyrinthShopItemKind = LabyrinthShopItemKind.UNKNOWN,
    val titleText: String? = null,
    val roleImprintLabel: String? = null,
    /** True for 选择印记, which opens a character picker after purchase. */
    val choiceRoleImprint: Boolean = false,
    val titleEvidenceId: Long? = null,
    val categoryState: LabyrinthShopCategoryState = LabyrinthShopCategoryState.READY,
    val categoryAttempt: Int = 0,
    /** Resource price after the run's frozen discounts; null is unknown, zero is not listed. */
    val price: Long? = null,
) {
    val purchasable: Boolean get() = status == LabyrinthShopItemStatus.AVAILABLE &&
        price != 0L && relicMatch?.basePrice != 0
}

/** Pure helper so noisy shop-title OCR can be tested without Android/ML Kit. */
object LabyrinthShopItemText {
    private val roleLabels = listOf("坦克", "治疗", "强化", "增益", "减益", "干扰", "攻击", "破防")

    private fun normalized(text: String?): String = text.orEmpty()
        .replace(Regex("\\s+"), "")
        .replace('職', '职')
        .replace('隨', '随')
        .replace("選擇", "选择")
        .replace('記', '记')
        .replace('紀', '记')
        .replace('纪', '记')
        .replace('増', '增')
        .let { value ->
            // A single substitution is allowed only inside a complete, known imprint title.
            // Do not globally replace OCR characters in ordinary relic names.
            roleLabels.fold(value) { result, role ->
                result.replace(Regex("${role}型(?:[取识職]?职能|[职识職取][能]|职.)的?(随机|选择)印记")) { match ->
                    "${role}型职能的${match.groupValues[1]}印记"
                }
            }
        }

    /** 确认购买必须显式读出选择或随机，缺字或两者同时出现均不能猜测。 */
    fun confirmedImprintChoice(text: String?): Boolean? {
        val value = normalized(text)
        return when {
            value.contains("选择") && !value.contains("随机") -> true
            value.contains("随机") && !value.contains("选择") -> false
            else -> null
        }
    }

    /** Coarse semantic gate; an uncertain role must still not be treated as a relic. */
    fun looksLikeRoleImprint(text: String?): Boolean {
        val value = normalized(text)
        if (value.contains("全体选择印记")) return true
        if (!value.contains("职能")) return false
        // Shop OCR occasionally drops/misreads the last "印记" glyph while preserving the much
        // more distinctive "职能...随机/选择" title. That evidence is sufficient to reject the
        // relic hypothesis; ordinary relic titles do not contain "职能".
        return value.contains("印记") || value.contains("随机") || value.contains("选择")
    }

    /**
     * Whether this imprint lets the player pick the character (选择印记) rather than rolling one
     * (随机印记).
     *
     * The variants share a role class but use separate catalog entries and prices. Only the choice variant
     * opens a full-roster 角色选择 page after purchase, which the run then has to answer.
     */
    fun isChoiceRoleImprint(text: String?): Boolean {
        val value = normalized(text)
        if (!looksLikeRoleImprint(value)) return false
        if (value.contains("随机")) return false
        return value.contains("选择")
    }

    /**
     * 标题 OCR 偶尔吞掉角色名的第一个字（干扰型 → 扰型）。只有剩余片段唯一指向一个角色时才认；
     * 「增益/减益」这类同尾字的角色宁可判不出，也不猜成另一个。
     */
    private fun roleWithLostLeadingGlyph(value: String): String? =
        roleLabels.filter { label ->
            label.length > 1 && (value.contains(label.drop(1) + "型职能") || value.contains(label.drop(1) + "职能"))
        }.singleOrNull()

    fun roleImprintLabel(text: String?): String? {
        val normalized = normalized(text)
        if (!looksLikeRoleImprint(normalized)) return null
        val role = roleLabels.firstOrNull { label ->
            normalized.contains("${label}型职能") || normalized.contains("${label}职能")
        } ?: roleWithLostLeadingGlyph(normalized)
        if (role != null && (normalized.contains("选择") || normalized.contains("随机"))) {
            return "${role}型职能印记"
        }
        if (normalized.contains("全体选择印记")) return "全体选择印记"
        if (normalized.contains("随机职能") &&
            (normalized.contains("选择印记") || normalized.contains("随机印记"))
        ) return "随机职能印记"
        return null
    }
}

data class LabyrinthShopObservation(
    val items: List<LabyrinthShopItemMatch>,
    val dialogState: LabyrinthShopDialogState = LabyrinthShopDialogState.NONE,
    val refreshButtonEnabledEvidence: Double = 0.0,
    /** The relic shown in the purchase-confirmation dialog, if the icon is trusted. */
    val purchaseCandidate: LabyrinthRelicMatch? = null,
    /** 当前购买确认弹窗的商品标题；商品卡和计划标签不能填入此字段。 */
    val confirmationTitle: LabyrinthShopReceiptRead? = null,
    /** 疑似回执正在读取时也必须阻止底层商店操作。 */
    val purchaseReceipt: LabyrinthShopPurchaseReceiptObservation? = null,
    val stockEpoch: Long = 0,
    val viewport: LabyrinthShopViewport = LabyrinthShopViewport.UNKNOWN,
    val contentOffset: Int = 0,
    val viewportPixels: LabyrinthShopViewportPixels? = null,
    val coverage: LabyrinthShopCoverage? = null,
    /** 当前通用商店弹窗的标题独立双读；不使用计划或历史页面名称。 */
    val refreshConfirmationTitle: LabyrinthShopReceiptRead? = null,
    /** Absence comes from the empty card interior, never an unread or disabled button. */
    val emptySlotIds: Set<String> = emptySet(),
) {
    val stockComplete: Boolean get() = coverage?.let {
        it.epoch == stockEpoch && it.complete(items)
    } == true
    val availableItemCount: Int get() = items.count(LabyrinthShopItemMatch::purchasable)
    val recognizedButtonCount: Int get() = items.count { it.buyButtonScore >= BUTTON_MIN_CONFIDENCE }
    val refreshButtonEnabled: Boolean get() = refreshButtonEnabledEvidence >= 0.50

    private companion object {
        const val BUTTON_MIN_CONFIDENCE = 0.55
    }
}

/**
 * Reads six physical positions and the observed button translation between top and bottom views.
 * The purchase confirmation dialog contains a larger copy of the purchased relic icon. That icon
 * is used as the commit candidate; the run store is updated only after the following purchase-
 * complete dialog, so opening the shop or cancelling a purchase cannot add a relic.
 */
class LabyrinthShopRecognizer(
    private val buyButtonTemplate: PixelImage? = null,
    private val relicTemplates: List<LabyrinthRelicTemplate> = emptyList(),
    private val matcher: GradientTemplateMatcher = GradientTemplateMatcher(maxSamples = 1_200),
    private val minButtonConfidence: Double = 0.55,
    private val minRelicConfidence: Double = 0.50,
    private val minRelicMargin: Double = 0.05,
) {
    private val relicMatcher = AlignedRgbRelicMatcher()

    init {
        require(minButtonConfidence in 0.0..1.0)
        require(minRelicConfidence in 0.0..1.0)
        require(minRelicMargin in 0.0..1.0)
        require(relicTemplates.all { it.relicId.isNotBlank() && it.attribute.isNotBlank() })
    }

    fun recognize(
        frame: PixelImage,
        dialogState: LabyrinthShopDialogState = LabyrinthShopDialogState.NONE,
        modalVisible: Boolean = false,
    ): LabyrinthShopObservation {
        if (dialogState != LabyrinthShopDialogState.NONE || modalVisible) {
            return LabyrinthShopObservation(emptyList(), dialogState,
                purchaseCandidate = if (dialogState == LabyrinthShopDialogState.PURCHASE_CONFIRMATION)
                    recognizePurchaseCandidate(frame) else null)
        }
        // Search a shared vertical translation, rather than assigning categories to rows.
        val offsets = (0..150 step 5).map { offset ->
            offset to TOP_ROW_SLOTS.map { slot ->
                buyButtonTemplate?.let { matcher.score(frame, map(frame,
                    slot.buyButtonRect.copy(y = slot.buyButtonRect.y - offset)), it, minimumChannelScore = 0.0) } ?: 0.0
            }
        }
        val best = offsets.maxWithOrNull(compareBy<Pair<Int, List<Double>>> {
            it.second.count { score -> score >= minButtonConfidence }
        }.thenBy { it.second.sum() })!!
        val offset = best.first
        val topVisible = TOP_ROW_SLOTS.mapIndexed { i, slot ->
            best.second[i] >= minButtonConfidence || emptyCardInterior(frame,
                slot.cardRect.copy(y = slot.cardRect.y - offset))
        }.all { it }
        val slots = (0..1).flatMap { row -> TOP_ROW_SLOTS.mapIndexed { column, slot ->
            val dy = row * 380 - offset
            slot.copy(id = LabyrinthShopSlots.keys[row * 3 + column],
                cardRect = slot.cardRect.copy(y = slot.cardRect.y + dy),
                buyButtonRect = slot.buyButtonRect.copy(y = slot.buyButtonRect.y + dy),
                iconRect = slot.iconRect.copy(y = slot.iconRect.y + dy))
        } }
        val items = slots.map { slot ->
            val screenRect = map(frame, slot.cardRect)
            val buyButtonRect = map(frame, slot.buyButtonRect)
            val iconRect = map(frame, slot.iconRect)
            val fullyVisible = slot.buyButtonRect.y >= 202 && slot.buyButtonRect.y + slot.buyButtonRect.height <= 875
            val score = if (fullyVisible) buyButtonTemplate?.let {
                matcher.score(frame, buyButtonRect, it, minimumChannelScore = 0.0)
            } ?: 0.0 else 0.0
            val enabledEvidence = if (fullyVisible) buyButtonEnabledEvidence(frame, buyButtonRect) else 0.0
            val relicMatch = if (offset <= 15 && topVisible) matchRelic(frame, slot.id, screenRect, iconRect) else null
            LabyrinthShopItemMatch(
                slotId = slot.id,
                screenRect = screenRect,
                buyButtonRect = buyButtonRect,
                buyButtonScore = score,
                buyButtonEnabledEvidence = enabledEvidence,
                status = when {
                    score < minButtonConfidence -> LabyrinthShopItemStatus.UNKNOWN
                    enabledEvidence >= MIN_ENABLED_BUTTON_EVIDENCE -> LabyrinthShopItemStatus.AVAILABLE
                    enabledEvidence <= 0.20 -> LabyrinthShopItemStatus.DISABLED
                    else -> LabyrinthShopItemStatus.UNKNOWN
                },
                relicMatch = relicMatch,
                kind = if (relicMatch?.recognized == true) LabyrinthShopItemKind.RELIC else LabyrinthShopItemKind.UNKNOWN,
            )
        }
        val emptySlots = slots.filter { emptyCardInterior(frame, it.cardRect) }.mapTo(mutableSetOf()) { it.id }
        val viewport = when {
            !topVisible || best.second.none { it >= minButtonConfidence } -> LabyrinthShopViewport.UNKNOWN
            offset <= 15 -> LabyrinthShopViewport.A
            offset in 65..150 && items.all { it.buyButtonScore >= minButtonConfidence || it.slotId in emptySlots } -> LabyrinthShopViewport.B
            else -> LabyrinthShopViewport.UNKNOWN
        }
        return LabyrinthShopObservation(
            items = items,
            dialogState = dialogState,
            refreshButtonEnabledEvidence = buyButtonEnabledEvidence(frame, map(frame, REFRESH_BUTTON_RECT)),
            viewport = viewport,
            contentOffset = offset,
            viewportPixels = if (dialogState == LabyrinthShopDialogState.NONE) LabyrinthShopViewportPixels.sample(frame) else null,
            emptySlotIds = emptySlots,
        )
    }

    private fun emptyCardInterior(frame: PixelImage, rect: EntryReferenceRect): Boolean {
        val visibleTop = maxOf(rect.y + 15, 202)
        val visibleBottom = minOf(rect.y + rect.height - 15, 875)
        if (visibleBottom - visibleTop < 220) return false
        return LabyrinthShopEmptySlotEvidence.matches(frame, map(frame,
            EntryReferenceRect(rect.x + 30, visibleTop, rect.width - 60, visibleBottom - visibleTop)))
    }

    private fun recognizePurchaseCandidate(frame: PixelImage): LabyrinthRelicMatch? {
        val trustedCandidates = PURCHASE_DIALOG_ICON_RECTS.mapNotNull { referenceRect ->
            val rect = map(frame, referenceRect)
            matchRelic(frame, "shop_purchase_confirmation", rect, rect)?.takeIf(LabyrinthRelicMatch::recognized)
        }
        // All trusted layout variants must agree on the exact relic identity.
        val trustedIds = trustedCandidates.mapNotNull(LabyrinthRelicMatch::relicId).distinct()
        return if (trustedIds.size == 1) trustedCandidates.maxByOrNull(LabyrinthRelicMatch::confidence) else null
    }

    private fun map(frame: PixelImage, rect: EntryReferenceRect): EntryPixelRect =
        ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = rect,
        ) ?: rect.toPixelRect()

    /**
     * The game keeps the same button frame/text when a shop purchase is unaffordable, but dims
     * the blue fill substantially. Gradient/template correlation therefore remains high and is
     * not sufficient to decide whether the control is actionable.
     *
     * Measure only the inner blue body so the bright gold/white border does not make a disabled
     * button look enabled. Live 1920x1080 fixtures give about 156 average luma for an enabled Buy
     * button, while the insufficient-currency state is about 95; the deliberately wide transition
     * band below leaves room for capture/compression variance.
     */
    internal fun buyButtonEnabledEvidence(frame: PixelImage, rect: EntryPixelRect): Double {
        val left = (rect.left + rect.width * 0.15).roundToInt().coerceIn(0, frame.width)
        val right = (rect.left + rect.width * 0.85).roundToInt().coerceIn(0, frame.width)
        val top = (rect.top + rect.height * 0.20).roundToInt().coerceIn(0, frame.height)
        val bottom = (rect.top + rect.height * 0.80).roundToInt().coerceIn(0, frame.height)
        if (right <= left || bottom <= top) return 0.0

        var luminanceSum = 0.0
        var samples = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = frame[x, y]
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                luminanceSum += red * 0.2126 + green * 0.7152 + blue * 0.0722
                samples++
                x += 3
            }
            y += 3
        }
        if (samples == 0) return 0.0
        val meanLuminance = luminanceSum / samples
        return ((meanLuminance - DISABLED_BUTTON_LUMA) /
            (ENABLED_BUTTON_LUMA - DISABLED_BUTTON_LUMA)).coerceIn(0.0, 1.0)
    }

    private fun matchRelic(
        frame: PixelImage,
        slotId: String,
        screenRect: EntryPixelRect,
        iconRect: EntryPixelRect,
    ): LabyrinthRelicMatch? {
        if (relicTemplates.isEmpty()) return null
        val ranked = relicMatcher.rank(frame, iconRect, relicTemplates)
        val best = ranked.firstOrNull()
        val secondScore = ranked.getOrNull(1)?.second ?: 0.0
        val score = best?.second ?: 0.0
        val trusted = best != null &&
            score >= minRelicConfidence &&
            score - secondScore >= minRelicMargin
        val template = best?.first?.takeIf { trusted }
        return LabyrinthRelicMatch(
            slotId = slotId,
            relicId = template?.relicId,
            displayName = template?.displayName,
            attribute = template?.attribute,
            attributeBonus = template?.attributeBonus,
            effect = template?.effect,
            basePrice = template?.basePrice,
            rarity = template?.rarity,
            confidence = score,
            screenRect = screenRect,
            iconRect = iconRect,
            suspectedRelicId = best?.first?.relicId,
            suspectedDisplayName = best?.first?.displayName,
            suspectedAttribute = best?.first?.attribute,
            rivalConfidence = secondScore,
            rivalMargin = score - secondScore,
        )
    }

    private data class Slot(
        val id: String,
        val cardRect: EntryReferenceRect,
        val buyButtonRect: EntryReferenceRect,
        val iconRect: EntryReferenceRect,
    )

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val TOP_ROW_SLOTS = listOf(
            Slot(
                id = "shop_item_1",
                cardRect = EntryReferenceRect(84, 220, 558, 357),
                buyButtonRect = EntryReferenceRect(350, 480, 290, 90),
                iconRect = EntryReferenceRect(100, 315, 155, 155),
            ),
            Slot(
                id = "shop_item_2",
                cardRect = EntryReferenceRect(662, 220, 556, 357),
                buyButtonRect = EntryReferenceRect(928, 480, 290, 90),
                iconRect = EntryReferenceRect(679, 315, 155, 155),
            ),
            Slot(
                id = "shop_item_3",
                cardRect = EntryReferenceRect(1237, 220, 559, 357),
                buyButtonRect = EntryReferenceRect(1506, 480, 290, 90),
                iconRect = EntryReferenceRect(1254, 315, 155, 155),
            ),
        )
        val PURCHASE_DIALOG_ICON_RECTS = listOf(
            // Original 480x270 video fixture mapped to the standard 1920x1080 canvas.
            EntryReferenceRect(525, 410, 205, 205),
            // Current client: live scan locates the 195px icon at (534, 419).
            EntryReferenceRect(534, 419, 195, 195),
        )
        val REFRESH_BUTTON_RECT = EntryReferenceRect(500, 930, 350, 85)

        const val DISABLED_BUTTON_LUMA = 110.0
        const val ENABLED_BUTTON_LUMA = 145.0
        const val MIN_ENABLED_BUTTON_EVIDENCE = 0.50

        fun EntryReferenceRect.toPixelRect() = EntryPixelRect(x, y, width, height)
    }
}

/**
 * Relic icons are full-colour catalog assets. The generic gradient matcher intentionally drops
 * chroma and works well for page chrome, but on the shop it made visually unrelated relics rank
 * almost identically. This matcher keeps RGB spatial structure and tolerates the few-pixel
 * rendering offset seen between the bundled 128px icon and the in-game shop card.
 *
 * To keep frame latency bounded, every template is scored only once at the nominal position.
 * Only the best coarse candidates receive the small alignment search.
 */
internal class AlignedRgbRelicMatcher(
    private val maxSamples: Int = 600,
    private val refinementCandidates: Int = 8,
) {
    init {
        require(maxSamples > 0)
        require(refinementCandidates > 0)
    }

    fun rank(
        frame: PixelImage,
        rect: EntryPixelRect,
        templates: List<LabyrinthRelicTemplate>,
    ): List<Pair<LabyrinthRelicTemplate, Double>> {
        if (templates.isEmpty() || rect.width < 3 || rect.height < 3) return emptyList()
        val coarse = templates.map { template ->
            template to score(frame, rect, template.image, offsetX = 0, offsetY = 0)
        }.sortedByDescending { it.second }
        val refineIds = coarse.take(refinementCandidates).mapTo(hashSetOf()) { it.first.relicId }
        val radiusX = max(2, (rect.width * ALIGN_RADIUS_RATIO).roundToInt())
        val radiusY = max(2, (rect.height * ALIGN_RADIUS_RATIO).roundToInt())
        val stepX = max(1, (rect.width * ALIGN_STEP_RATIO).roundToInt())
        val stepY = max(1, (rect.height * ALIGN_STEP_RATIO).roundToInt())

        return coarse.map { (template, coarseScore) ->
            if (template.relicId !in refineIds) {
                template to coarseScore
            } else {
                var best = coarseScore
                var bestX = 0
                var bestY = 0
                var dx = -radiusX
                while (dx <= radiusX) {
                    var dy = -radiusY
                    while (dy <= radiusY) {
                        if (dx != 0 || dy != 0) {
                            val candidate = score(frame, rect, template.image, dx, dy)
                            if (candidate > best) { best = candidate; bestX = dx; bestY = dy }
                        }
                        dy += stepY
                    }
                    dx += stepX
                }
                // At a 155 px shop icon the coarse grid advances two pixels, so it can miss
                // a one-pixel alignment and turn a clear icon into a low-margin candidate.
                // Refine every shortlisted competitor equally, using the same pixels/metric,
                // within the original radius. This is one bounded neighborhood, not hill climbing.
                val centerX = bestX
                val centerY = bestY
                if (stepX > 1 || stepY > 1) {
                    for (fineX in maxOf(-radiusX, centerX - 1)..minOf(radiusX, centerX + 1)) {
                        for (fineY in maxOf(-radiusY, centerY - 1)..minOf(radiusY, centerY + 1)) {
                            if (fineX == centerX && fineY == centerY) continue
                            val onCoarseGrid = (fineX + radiusX) % stepX == 0 &&
                                (fineY + radiusY) % stepY == 0
                            if (onCoarseGrid || (fineX == 0 && fineY == 0)) continue
                            best = max(best, score(frame, rect, template.image, fineX, fineY))
                        }
                    }
                }
                template to best
            }
        }.sortedByDescending { it.second }
    }

    private fun score(
        frame: PixelImage,
        rect: EntryPixelRect,
        template: PixelImage,
        offsetX: Int,
        offsetY: Int,
    ): Double {
        if (template.width < 3 || template.height < 3) return 0.0
        val step = max(
            1,
            ceil(sqrt(template.width.toDouble() * template.height / maxSamples)).toInt(),
        )
        var squaredError = 0.0
        var samples = 0
        var templateY = 1
        while (templateY < template.height - 1) {
            var templateX = 1
            while (templateX < template.width - 1) {
                val templatePixel = template[templateX, templateY]
                if ((templatePixel ushr 24 and 0xff) >= MIN_TEMPLATE_ALPHA) {
                    val frameX = rect.left + offsetX +
                        mapCoordinate(templateX, template.width, rect.width)
                    val frameY = rect.top + offsetY +
                        mapCoordinate(templateY, template.height, rect.height)
                    if (frameX in 0 until frame.width && frameY in 0 until frame.height) {
                        val framePixel = frame[frameX, frameY]
                        val red = (framePixel ushr 16 and 0xff) - (templatePixel ushr 16 and 0xff)
                        val green = (framePixel ushr 8 and 0xff) - (templatePixel ushr 8 and 0xff)
                        val blue = (framePixel and 0xff) - (templatePixel and 0xff)
                        squaredError += (red * red + green * green + blue * blue).toDouble()
                        samples++
                    }
                }
                templateX += step
            }
            templateY += step
        }
        if (samples == 0) return 0.0
        val rmse = sqrt(squaredError / (samples * 3.0))
        return (1.0 - rmse / 255.0).coerceIn(0.0, 1.0)
    }

    private fun mapCoordinate(value: Int, sourceSize: Int, targetSize: Int): Int =
        (value.toLong() * (targetSize - 1) / (sourceSize - 1)).toInt()

    private companion object {
        const val MIN_TEMPLATE_ALPHA = 128
        const val ALIGN_RADIUS_RATIO = 0.04
        const val ALIGN_STEP_RATIO = 0.013
    }
}

fun LabyrinthEntryPageState.toShopDialogState(): LabyrinthShopDialogState = when (this) {
    LabyrinthEntryPageState.SHOP -> LabyrinthShopDialogState.NONE
    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION -> LabyrinthShopDialogState.PURCHASE_CONFIRMATION
    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE -> LabyrinthShopDialogState.PURCHASE_COMPLETE
    LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION -> LabyrinthShopDialogState.EXIT_CONFIRMATION
    else -> LabyrinthShopDialogState.NONE
}
