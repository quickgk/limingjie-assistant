package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.LabyrinthRelicStackLedger.Source
import org.junit.Assert.*
import org.junit.Test

class LabyrinthRelicStackLedgerTest {
    private val ledger = LabyrinthRelicStackLedger()
    private val mark = LabyrinthRelicMark.DEFENSE
    private fun observe(value: Int?, source: Source, id: Long) = ledger.observe(mark, value, source, "slot", id)
    private fun confirm(value: Int, source: Source, firstId: Long = 1) {
        observe(value, source, firstId); observe(value, source, firstId + 1)
    }
    @Test fun `repeated cache evidence is only one vote`() {
        repeat(20) { observe(9, Source.CHOICE, 1) }
        assertFalse(ledger.stacks.containsKey(mark))
        observe(9, Source.CHOICE, 2)
        assertEquals(9, ledger.stacks[mark])
    }
    @Test fun `tiny map counters cannot overwrite card or acquisition totals`() {
        confirm(9, Source.CHOICE)
        ledger.acquired(mark, 1, 0)
        confirm(1, Source.MAP)
        assertEquals(10, ledger.stacks[mark])
        assertTrue(ledger.needsAudit)
    }
    @Test fun `visible details can correct a wrong total without clearing unseen marks`() {
        ledger.acquired(mark, 9, 0)
        ledger.acquired(LabyrinthRelicMark.CRITICAL, 3, 0)
        confirm(4, Source.DETAIL)
        assertEquals(4, ledger.stacks[mark])
        assertEquals(3, ledger.stacks[LabyrinthRelicMark.CRITICAL])
        assertFalse(ledger.stacks.containsKey(LabyrinthRelicMark.ACCELERATION))
    }
    @Test fun `cached pre-acquisition readings do not roll back a reward`() {
        confirm(9, Source.CHOICE)
        ledger.acquired(mark, 1, 0)
        repeat(10) { observe(9, Source.CHOICE, 2) }
        assertEquals(10, ledger.stacks[mark])
    }
    @Test fun `uncertain or contradictory readings cannot form consensus`() {
        observe(9, Source.CHOICE, 1)
        observe(null, Source.CHOICE, 2)
        observe(9, Source.CHOICE, 3)
        observe(1, Source.CHOICE, 4)
        assertTrue(ledger.stacks.isEmpty())
    }
    @Test fun `new run clears evidence totals and conflicts`() {
        confirm(4, Source.DETAIL)
        confirm(1, Source.MAP)
        ledger.clear()
        assertTrue(ledger.stacks.isEmpty())
        assertFalse(ledger.needsAudit)
        confirm(1, Source.CHOICE)
        assertEquals(1, ledger.stacks[mark])
    }

    @Test fun `restored acquisitions cannot be replaced by tiny map digits`() {
        ledger.seedAcquisitions(mapOf(mark to 9, LabyrinthRelicMark.CRITICAL to 0))
        confirm(1, Source.MAP)
        assertEquals(9, ledger.stacks[mark])
        assertFalse(ledger.stacks.containsKey(LabyrinthRelicMark.CRITICAL))
        assertTrue(ledger.needsAudit)
    }

    @Test fun `out of order evidence cannot manufacture new confirmations`() {
        confirm(9, Source.CHOICE, 10)
        ledger.acquired(mark, 1, 0)
        observe(1, Source.CHOICE, 9)
        observe(1, Source.CHOICE, 10)
        assertEquals(10, ledger.stacks[mark])
    }

    /** 单次地图误读不该让整局都挂着「需核对」：地图后来读回记录值就自行消除。 */
    @Test fun `地图读数与记录一致后冲突自行消除`() {
        confirm(9, Source.CHOICE)
        confirm(1, Source.MAP, firstId = 10)
        assertTrue(ledger.needsAudit)
        assertEquals("守备（记录9/地图1）", ledger.conflictSummary())
        confirm(9, Source.MAP, firstId = 20)
        assertFalse(ledger.needsAudit)
        assertEquals(9, ledger.stacks[mark])
    }

    /** 冲突提示要能看出是哪个系列、两边各是多少。 */
    @Test fun `冲突明细写出记录值与地图值`() {
        confirm(9, Source.CHOICE)
        confirm(1, Source.MAP, firstId = 10)
        assertTrue(ledger.conflictSummary().contains("记录9"))
        assertTrue(ledger.conflictSummary().contains("地图1"))
    }

    /** 地图数字是全局总数，连续三帧一致就该纠正偏高的记录。 */
    @Test fun `地图连续三帧一致时纠正记录`() {
        ledger.acquired(mark, 9, 0)
        assertEquals(9, ledger.stacks[mark])
        repeat(3) { offset -> observe(4, Source.MAP, 10L + offset) }
        assertEquals(4, ledger.stacks[mark])
        assertFalse(ledger.needsAudit)
    }

    /** 两帧一致还不够——数字小、易误读，误覆盖的代价比多等一帧大。 */
    @Test fun `地图两帧一致仍不足以覆盖记录`() {
        ledger.acquired(mark, 9, 0)
        repeat(2) { offset -> observe(4, Source.MAP, 10L + offset) }
        assertEquals(9, ledger.stacks[mark])
        assertTrue(ledger.needsAudit)
    }
}
