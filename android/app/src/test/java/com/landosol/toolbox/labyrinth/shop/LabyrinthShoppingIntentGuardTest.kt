package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.SHOPPING_HOLD_TIMEOUT_MILLIS
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待决购买意图的回收判定。
 *
 * 意图悬空后会在 SHOP/UNKNOWN 页无限 hold，把 BOSS 特别装备流程也一起挡掉。
 */
class LabyrinthShoppingIntentGuardTest {

    private val now = 1_800_000_000_000L

    private fun intent(epochMillis: Long?, nodeId: Long? = null, floor: Int? = null) = buildJsonObject {
        if (epochMillis != null) put("timestamp_utc", Instant.ofEpochMilli(epochMillis).toString())
        if (nodeId != null) put("node_id", nodeId)
        if (floor != null) put("floor", floor)
    }

    @Test
    fun `年龄按事件时间戳计算`() {
        assertEquals(42_000L, LabyrinthShoppingIntentGuard.ageMillis(intent(now - 42_000), now))
    }

    /** 缺时间戳、格式错误、时间戳在未来（设备时钟回拨）都算「年龄未知」。 */
    @Test
    fun `时间戳缺失或不可用时年龄未知`() {
        assertNull(LabyrinthShoppingIntentGuard.ageMillis(buildJsonObject { }, now))
        assertNull(
            LabyrinthShoppingIntentGuard.ageMillis(
                buildJsonObject { put("timestamp_utc", "not-a-time") },
                now,
            ),
        )
        assertNull(LabyrinthShoppingIntentGuard.ageMillis(intent(now + 5_000), now))
    }

    @Test
    fun `超过作废期限才作废`() {
        assertFalse(LabyrinthShoppingIntentGuard.shouldAbandon(null))
        assertFalse(LabyrinthShoppingIntentGuard.shouldAbandon(0L))
        assertFalse(LabyrinthShoppingIntentGuard.shouldAbandon(LabyrinthShoppingIntentGuard.ABANDON_MILLIS))
        assertTrue(LabyrinthShoppingIntentGuard.shouldAbandon(LabyrinthShoppingIntentGuard.ABANDON_MILLIS + 1))
    }

    @Test
    fun `节点不同即视为已离开`() {
        val pending = intent(now, nodeId = 7L, floor = 2)
        assertTrue(LabyrinthShoppingIntentGuard.movedAway(pending, nodeId = 8L, floor = 2))
        assertFalse(LabyrinthShoppingIntentGuard.movedAway(pending, nodeId = 7L, floor = 2))
    }

    @Test
    fun `同节点换层也算已离开`() {
        val pending = intent(now, nodeId = 7L, floor = 2)
        assertTrue(LabyrinthShoppingIntentGuard.movedAway(pending, nodeId = 7L, floor = 3))
    }

    /** 任一方节点或层未知时不动手：拿不准就保持原状，宁可继续 hold。 */
    @Test
    fun `节点或层未知时不判为离开`() {        val noNode = intent(now)
        assertFalse(LabyrinthShoppingIntentGuard.movedAway(noNode, nodeId = 8L, floor = 2))
        val pending = intent(now, nodeId = 7L, floor = 2)
        assertFalse(LabyrinthShoppingIntentGuard.movedAway(pending, nodeId = null, floor = 2))
        assertFalse(LabyrinthShoppingIntentGuard.movedAway(pending, nodeId = 7L, floor = null))
        // 意图本身没带 floor 时，只比节点
        val noFloor = buildJsonObject {
            put("timestamp_utc", Instant.ofEpochMilli(now).toString())
            put("node_id", 7L)
        }
        assertFalse(LabyrinthShoppingIntentGuard.movedAway(noFloor, nodeId = 7L, floor = 9))
        assertTrue(LabyrinthShoppingIntentGuard.movedAway(noFloor, nodeId = 8L, floor = 9))
    }

    /**
     * 兜底作废必须晚于「连续 hold 满 180 秒停止整局」。
     *
     * 反过来会让作废抢在保守停止前面，等于悄悄废掉那条 fail-closed 契约。
     */
    @Test
    fun `作废期限晚于购物 hold 停止期限`() {
        assertTrue(
            "ABANDON_MILLIS=${LabyrinthShoppingIntentGuard.ABANDON_MILLIS} 必须大于 " +
                "SHOPPING_HOLD_TIMEOUT_MILLIS=$SHOPPING_HOLD_TIMEOUT_MILLIS",
            LabyrinthShoppingIntentGuard.ABANDON_MILLIS > SHOPPING_HOLD_TIMEOUT_MILLIS,
        )
    }
}
