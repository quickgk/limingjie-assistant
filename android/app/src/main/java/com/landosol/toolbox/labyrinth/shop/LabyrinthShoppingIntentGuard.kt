package com.landosol.toolbox.labyrinth.shop

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 待决购买意图的回收判定。
 *
 * 购买意图持久化在 ledger 中，由回执或明确放弃事件结束。
 * 无法得到当前确认身份和钱包时仍需安全回收出口，不能让旧意图阻塞后面的节点。
 *
 * 这里只做纯判定，不碰状态；调用方用 `LabyrinthShoppingModule.abandonPurchase` 结束等待，
 * 不把未知执行结果当成取消，也不补写交易事实。
 */
internal object LabyrinthShoppingIntentGuard {

    /**
     * 意图超过这个时长仍未结算，视为已经交回人工：作废后继续跑。
     *
     * 独立于连续 hold 的兜底年龄，防止页面进展反复刷新等待计时。
     * 超过年龄后由调用方放弃本店等待，未知结果交回人工核实。
     */
    const val ABANDON_MILLIS = 240_000L

    /**
     * 意图年龄；事件缺 `timestamp_utc` 或无法解析时返回 null。
     *
     * 返回 null 表示「年龄未知」，调用方应保守处理：不自动作废。
     */
    fun ageMillis(intent: JsonObject, now: Long): Long? =
        intent["timestamp_utc"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?.let { now - it }
            ?.takeIf { it >= 0L }

    /**
     * 是否该按超时作废。
     *
     * 年龄未知时返回 false，继续等待回执或人工处理。
     */
    fun shouldAbandon(ageMillis: Long?): Boolean =
        ageMillis != null && ageMillis > ABANDON_MILLIS

    /**
     * 意图是否已经离开它所属的节点/层。
     *
     * 已经换了节点，这次购买不可能还在这张图上，无需再为它保留人工控制。
     * 任一方的节点或层未知时返回 false，保持原状。
     */
    fun movedAway(intent: JsonObject, nodeId: Long?, floor: Int?): Boolean {
        val intentNode = intent["node_id"]?.jsonPrimitive?.longOrNull ?: return false
        val currentNode = nodeId ?: return false
        if (intentNode != currentNode) return true
        val intentFloor = intent["floor"]?.jsonPrimitive?.intOrNull ?: return false
        val currentFloor = floor ?: return false
        return intentFloor != currentFloor
    }
}
