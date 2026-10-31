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
 * 购买意图持久化在 ledger 里，只能靠财务回执结算；预算租约（[ShoppingBudgetLease]）只在内存里，
 * 页面一变就失效。两者一旦脱钩，意图会永久悬空，把之后每个商店页和 UNKNOWN 页都拦成人工控制，
 * BOSS 特别装备流程也会跟着卡住。
 *
 * 这里只做纯判定，不碰状态；真正的作废动作由 `LabyrinthShoppingModule.cancelIntent` 落成
 * ledger 事件，因此「为什么作废」在诊断包里可查。
 */
internal object LabyrinthShoppingIntentGuard {

    /**
     * 意图超过这个时长仍未结算，视为已经交回人工：作废后继续跑，而不是无限 hold。
     *
     * **必须大于 `SHOPPING_HOLD_TIMEOUT_MILLIS`（180s）**。连续 hold 满 180 秒时，保守策略是
     * 停止整局并保留诊断，那个出口优先；这条超时只在「页面反复变化、hold 计时被不断打断、
     * 整局一直不停」的场景里兜底。
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
     * 年龄未知时返回 false：宁可继续 hold 等人工，也不误清一次可能真实发生的购买。
     */
    fun shouldAbandon(ageMillis: Long?): Boolean =
        ageMillis != null && ageMillis > ABANDON_MILLIS

    /**
     * 意图是否已经离开它所属的节点/层。
     *
     * 已经换了节点，这次购买不可能还在这张图上，无需再为它保留人工控制。
     * 任一方的节点或层未知时返回 false —— 拿不准就不动。
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
