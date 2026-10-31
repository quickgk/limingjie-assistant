package com.landosol.toolbox.labyrinth

/** Unknown marks are absent, not zero. Cached evidence cannot manufacture consensus. */
internal class LabyrinthRelicStackLedger {
    enum class Source { MAP, CHOICE, DETAIL, ACQUISITION }
    private data class Pending(val value: Int, val source: Source, val count: Int)

    private companion object {
        /**
         * 地图数字要连续多少帧读数一致才允许覆盖记录。
         *
         * 比其它来源（2 帧）更严：地图数字字号小、又是唯一能纠正累加误差的来源，
         * 误覆盖的代价比多等一帧大。三帧约 1 秒，正常停留足够。
         */
        const val MAP_CONFIRMATIONS = 3
    }
    private val values = mutableMapOf<LabyrinthRelicMark, Int>()
    private val sources = mutableMapOf<LabyrinthRelicMark, Source>()
    private val pending = mutableMapOf<LabyrinthRelicMark, Pending>()
    private val evidence = mutableMapOf<String, Long>()
    /** mark → 地图上读到的那个值；用来在提示里直接写出「记录 X / 地图 Y」。 */
    private val conflicts = mutableMapOf<LabyrinthRelicMark, Int>()
    val stacks: Map<LabyrinthRelicMark, Int> get() = values
    val needsAudit: Boolean get() = conflicts.isNotEmpty()

    /** 冲突明细，直接进提示文本，避免只说「有冲突」却看不出是哪个系列、差多少。 */
    fun conflictSummary(): String = conflicts.entries.joinToString("、") { (mark, mapValue) ->
        "${mark.label}（记录${values[mark] ?: "?"}/地图$mapValue）"
    }

    fun clear() { values.clear(); sources.clear(); pending.clear(); evidence.clear(); conflicts.clear() }
    fun clearPending() { pending.clear() }

    fun seedAcquisitions(totals: Map<LabyrinthRelicMark, Int>) {
        totals.filterValues { it > 0 }.forEach { (mark, value) ->
            values[mark] = value.coerceAtMost(30)
            sources[mark] = Source.ACQUISITION
        }
    }

    fun observe(mark: LabyrinthRelicMark, value: Int?, source: Source, slot: String, evidenceId: Long?) {
        if (evidenceId == null) return
        val key = "$source:$slot"
        if (evidence[key]?.let { evidenceId <= it } == true) return
        evidence[key] = evidenceId
        if (value == null || value !in 0..30) { pending.remove(mark); return }
        val existing = values[mark]
        if (source == Source.MAP && sources[mark] != null && sources[mark] != Source.MAP) {
            // 地图左下角那个数字就是该系列的全局总数（玩家确认：显示 加速4 = 已有遗物累计 4 点），
            // 所以它本来就该能纠正记录——记录是从获取事件累加的，可能重复计或漏计。
            // 但数字小、易误读，要求连续 [MAP_CONFIRMATIONS] 帧读数一致才覆盖；
            // 在此之前只记冲突（后续一致读数可自愈），不改变已确认值。
            if (existing != value) conflicts[mark] = value else conflicts.remove(mark)
        }
        if (existing == value && (sources[mark]?.ordinal ?: -1) >= source.ordinal) {
            pending.remove(mark)
            if (source != Source.MAP) conflicts.remove(mark)
            return
        }
        val previous = pending[mark]
        val count = if (previous?.value == value && previous.source == source) previous.count + 1 else 1
        val required = if (source == Source.MAP) MAP_CONFIRMATIONS else 2
        if (count < required) { pending[mark] = Pending(value, source, count); return }
        values[mark] = value
        sources[mark] = source
        pending.remove(mark)
        conflicts.remove(mark)
    }

    /** Called once at an acknowledged reward transition, never on the click itself. */
    fun acquired(mark: LabyrinthRelicMark, bonus: Int, fallbackBefore: Int) {
        if (bonus <= 0) return
        values[mark] = ((values[mark] ?: fallbackBefore) + bonus).coerceIn(0, 30)
        sources[mark] = Source.ACQUISITION
        pending.remove(mark)
        // Keep seen IDs so a cached pre-acquisition card cannot restore the old total.
    }
}
