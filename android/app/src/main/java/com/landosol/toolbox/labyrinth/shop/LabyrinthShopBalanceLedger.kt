package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.shop.LabyrinthShopReceiptLedger.Companion.id
import com.landosol.toolbox.labyrinth.shop.LabyrinthShopReceiptLedger.Companion.payload
import com.landosol.toolbox.labyrinth.shop.LabyrinthShopReceiptLedger.Companion.type
import com.landosol.toolbox.labyrinth.vision.LabyrinthCurrencyObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopReceiptRead
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopPurchaseReceiptObservation
import java.io.FileOutputStream
import kotlinx.serialization.json.*

/** 只有区间内严格单一、有原图的事实来源才可解释净余额变化。 */
class LabyrinthShopBalanceLedger(private val store: LabyrinthShoppingJournal) {
    fun verifiedBalance(run: LabyrinthShoppingJournal.Run): Long? = synchronized(store.lock) {
        val events = store.read(run)
        // 未决刷新不能被后来的普通观察锚点洗掉；恢复时同时复核整个协议。
        if (refreshStates(run, events).any { !it.rejected && it.settled == null }) return@synchronized null
        val index = events.indexOfLast(::isAnchor)
        val anchor = events.getOrNull(index) ?: return@synchronized null
        check(store.evidenceValid(run, anchor)) { "已核实余额原图缺失或校验失败" }
        // prepared 已耐久但 executor 结果未知时也不能继续使用旧余额。
        val interval = events.drop(index + 1)
        val confirmations = interval.filter { type(it) in setOf("shop.purchase_confirm_prepared", "shop.purchase_confirm_rejected") }
        val released = mutableSetOf<String>()
        confirmations.map { payload(it)["intent_id"] }.distinct().forEach { intentId ->
            val intent = checkNotNull(events.singleOrNull { type(it) == "shop.purchase_intent" && it["event_id"] == intentId }) {
                "余额恢复缺少拒绝关联的精确意图"
            }
            val state = LabyrinthShopReceiptLedger(store).confirmation(run, intent, events)
            if (state.prepared == null && state.dispatched == null) {
                released += confirmations.filter { payload(it)["intent_id"] == intentId }.map(::id)
            }
        }
        released += rejectedRefreshEventIds(events)
        if (interval.any { type(it) !in NON_FINANCIAL && type(it) !in DIAGNOSTIC && id(it) !in released }) {
            return@synchronized null
        }
        payload(anchor)["balance_after"]?.jsonPrimitive?.longOrNull
    }

    fun observe(run: LabyrinthShoppingJournal.Run, balance: Long, nodeId: Long?, floor: Int?, now: Long,
        text: String?, evidenceId: Long?, encode: (FileOutputStream) -> Boolean,
        confirmations: Int = 2): Unit = synchronized(store.lock) {
        require(balance >= 0) { "余额不能为负" }
        val events = store.read(run)
        val refresh = refreshStates(run, events).singleOrNull { !it.rejected && it.settled == null }
        val actualRead = LabyrinthShopReceiptRead(text, evidenceId ?: -1, confirmations)
        if (refresh != null) {
            val preparedNode = refresh.prepared["node_id"]?.jsonPrimitive?.longOrNull
            val preparedFloor = refresh.prepared["floor"]?.jsonPrimitive?.intOrNull
            // 未决刷新期间只有两种读数会结束本次刷新：等于前余额（无变化），或满足
            // `前余额 - 读数 == 刷新价` 且区间干净（结算）。其余同节点同楼层的读数只留诊断，
            // 因为写锚点会让扣费区间多一条非财务事件，这次刷新就再也结算不了。
            if (readAmount(actualRead) == balance && evidenceId != null &&
                payload(refresh.prepared)["balance_before"]?.jsonPrimitive?.longOrNull == balance &&
                singleRefreshInterval(run, refresh.prepared, events, nodeId, floor)) return@synchronized
            if (readAmount(actualRead) == balance && evidenceId != null &&
                canSettleRefresh(run, refresh.prepared, events, balance, nodeId, floor)) {
                val prepared = refresh.prepared
                val key = "settle-${id(prepared)}"
                val evidence = store.evidence(run, key, encode)
                store.append(run, key, "shop.refresh_settled", nodeId, floor, now, buildJsonObject {
                    put("attempt_key", payload(prepared)["attempt_key"]!!); put("prepared_id", prepared["event_id"]!!)
                    put("anchor_id", payload(prepared)["anchor_id"]!!)
                    put("balance_before", payload(prepared)["balance_before"]!!); put("balance_after", balance)
                    put("spent", payload(prepared)["refresh_price"]!!); put("currency", "alpha")
                    put("attribution", "single_source_balance_interval")
                    put("interval_event_ids", JsonArray(refreshInterval(prepared, events).map { it["event_id"]!! }))
                    put("balance_read", readEvidence(LabyrinthShopReceiptRead(text, checkNotNull(evidenceId), confirmations)))
                }, evidence)
                return@synchronized
            }
            // 对不上的读数只留诊断：它既不能解释扣费，也不能污染区间。未决状态继续保持
            // fail-closed（verifiedBalance 仍为 null），但之后正确的扣费读数还能结算一次。
            //
            // 只在同一节点楼层内这么做。换了节点/楼层说明玩家已经离开这次刷新的现场，那条读数
            // 是另一处的真实余额事实，仍要落成锚点；此时该刷新本来也无法在店内核实。
            if (nodeId == preparedNode && floor == preparedFloor) {
                if (evidenceId != null) recordPendingRefreshDiagnostic(run, refresh.prepared, balance, nodeId, floor,
                    now, text, evidenceId, encode)
                return@synchronized
            }
        }
        val lastIndex = events.indexOfLast(::isAnchor)
        val last = events.getOrNull(lastIndex)
        if (last != null) check(store.evidenceValid(run, last)) { "上次余额原图缺失或校验失败" }
        val before = last?.let { payload(it)["balance_after"]?.jsonPrimitive?.longOrNull }
        val interval = events.drop(lastIndex + 1)
        if (before == balance && last?.get("node_id") == JsonPrimitive(nodeId) &&
            last?.get("floor") == JsonPrimitive(floor) &&
            interval.all { type(it) in NON_FINANCIAL || type(it) in DIAGNOSTIC }) return@synchronized
        val delta = before?.let { balance - it }
        val sources = interval.filter { type(it) in FINANCIAL_SOURCES }
        val source = sources.singleOrNull()
        val sourceType = source?.let(::type)
        val refreshFee = source?.let { payload(it)["refresh_price"]?.jsonPrimitive?.longOrNull }
        val knownInterval = interval.all { type(it) in NON_FINANCIAL || type(it) in FINANCIAL_SOURCES }
        val validSource = source != null && store.evidenceValid(run, source) && when (sourceType) {
            "battle.completed" -> source["verification_status"]?.jsonPrimitive?.content == "verified" &&
                source["source"]?.jsonPrimitive?.content == "actual_screen"
            "shop.refresh_dispatched" -> payload(source)["prepared_id"] == null &&
                source["verification_status"]?.jsonPrimitive?.content == "dispatched_only" &&
                source["source"]?.jsonPrimitive?.content == "automation_action"
            "event.choice_dispatched" -> source["verification_status"]?.jsonPrimitive?.content == "dispatched_only" &&
                source["source"]?.jsonPrimitive?.content == "automation_action"
            else -> false
        }
        val eventType = when {
            before == null || !knownInterval || !validSource -> "currency.observed"
            sourceType == "shop.refresh_dispatched" && refreshFee != null && refreshFee > 0 &&
                payload(source!!)["balance_before"]?.jsonPrimitive?.longOrNull == before && delta == -refreshFee -> "shop.refreshed"
            sourceType == "battle.completed" && payload(source!!)["outcome"]?.jsonPrimitive?.content == "won" &&
                delta != null && delta >= 0 -> "battle.reward"
            sourceType == "event.choice_dispatched" -> "event.reward"
            else -> "currency.observed"
        }
        val key = "wallet-" + LabyrinthShoppingJournal.key("$nodeId|$floor|$balance|${last?.let(::id)}|${interval.map(::id)}")
        val evidence = store.evidence(run, key, encode)
        store.append(run, key, eventType, nodeId, floor, now, buildJsonObject {
            put("balance_before", JsonPrimitive(before)); put("balance_after", balance)
            put("received", JsonPrimitive(delta?.takeIf { eventType != "currency.observed" && it >= 0 }))
            put("spent", JsonPrimitive(delta?.takeIf { eventType != "currency.observed" && it < 0 }?.let { -it }))
            put("unattributed_delta", JsonPrimitive(delta?.takeIf { eventType == "currency.observed" && it != 0L }))
            put("attribution", if (eventType == "currency.observed") "unattributed_balance_observation" else "single_source_balance_interval")
            put("attribution_source", "journal_interval_inference"); put("amount_basis", "net_balance_change")
            put("gross_amounts_known", eventType != "currency.observed")
            put("interval_event_ids", JsonArray(interval.map { it["event_id"]!! }))
            put("ocr_text", text); put("ocr_evidence_id", JsonPrimitive(evidenceId))
        }, evidence)
    }

    internal data class RefreshState(val prepared: JsonObject, val dispatched: JsonObject?,
        val rejected: Boolean, val settled: JsonObject?)

    internal fun prepareRefresh(run: LabyrinthShoppingJournal.Run, attemptKey: String, priceRead: LabyrinthShopReceiptRead,
        observation: LabyrinthCurrencyObservation, nodeId: Long, floor: Int, now: Long,
        encode: (FileOutputStream) -> Boolean): Boolean = synchronized(store.lock) {
        require(attemptKey.matches(Regex("[a-zA-Z0-9_-]+"))) { "刷新 attempt key 无效" }
        val price = checkNotNull(readAmount(priceRead)?.takeIf { it > 0 }) { "刷新费用必须实际独立双读" }
        val balance = checkNotNull(observation.balance) { "刷新前余额必须实际独立双读" }
        check(balance >= price) { "刷新前余额不足" }
        val events = store.read(run)
        val states = refreshStates(run, events)
        states.singleOrNull { payload(it.prepared)["attempt_key"] == JsonPrimitive(attemptKey) }?.let {
            check(it.prepared["node_id"] == JsonPrimitive(nodeId) && it.prepared["floor"] == JsonPrimitive(floor) &&
                payload(it.prepared)["refresh_price"] == JsonPrimitive(price) &&
                payload(it.prepared)["balance_before"] == JsonPrimitive(balance)) { "刷新 attempt 上下文变化" }
            return@synchronized false
        }
        check(states.none { !it.rejected && it.settled == null } &&
            LabyrinthShopReceiptLedger.pendingIntent(events) == null) { "已有购物操作待对账" }
        check(verifiedBalance(run) == balance) { "刷新前耐久余额未对账" }
        val anchor = checkNotNull(lastAnchor(run))
        check(anchor["node_id"] == JsonPrimitive(nodeId) && anchor["floor"] == JsonPrimitive(floor)) { "刷新余额锚点节点不符" }
        val key = "refresh-prepare-$attemptKey"
        val evidence = store.evidence(run, key, encode)
        store.append(run, key, "shop.refresh_prepared", nodeId, floor, now, buildJsonObject {
            put("attempt_key", attemptKey); put("refresh_price", price); put("balance_before", balance)
            put("anchor_id", anchor["event_id"]!!); put("currency", "alpha")
            put("price_read", readEvidence(priceRead)); put("balance_read", readEvidence(checkNotNull(observation.read)))
        }, evidence, verification = "prepared_only", source = "automation_action")
        true
    }

    internal fun refreshResult(run: LabyrinthShoppingJournal.Run, attemptKey: String, executed: Boolean, now: Long): Unit = synchronized(store.lock) {
        val state = checkNotNull(refreshStates(run, store.read(run)).singleOrNull {
            payload(it.prepared)["attempt_key"] == JsonPrimitive(attemptKey) }) { "缺少刷新 prepared" }
        if (executed && (state.settled != null || state.dispatched != null) || !executed && state.rejected) return@synchronized
        check(!state.rejected && state.settled == null && (executed || state.dispatched == null)) {
            "已完成或派发的刷新不能撤回，已拒绝的刷新不能迟到派发"
        }
        val prepared = state.prepared
        store.append(run, "${if (executed) "dispatch" else "reject"}-${id(prepared)}",
            if (executed) "shop.refresh_dispatched" else "shop.refresh_rejected",
            prepared["node_id"]!!.jsonPrimitive.long, prepared["floor"]!!.jsonPrimitive.int, now,
            buildJsonObject {
                put("attempt_key", attemptKey); put("prepared_id", prepared["event_id"]!!)
                put("refresh_price", payload(prepared)["refresh_price"]!!); put("balance_before", payload(prepared)["balance_before"]!!)
                if (!executed) put("result", "not_executed")
            }, prepared["evidence_ref"]!!.jsonPrimitive.content,
            verification = if (executed) "dispatched_only" else "rejected_not_executed", source = "automation_action")
    }

    internal fun refreshStates(run: LabyrinthShoppingJournal.Run, events: List<JsonObject> = store.read(run)): List<RefreshState> {
        val states = events.filter { type(it) == "shop.refresh_prepared" }.map { prepared ->
            val context = payload(prepared)
            val price = savedRead(context["price_read"])?.let(::readAmount)
            val balance = savedRead(context["balance_read"])?.let(::readAmount)
            check(store.evidenceValid(run, prepared) && prepared["node_id"]?.jsonPrimitive?.longOrNull != null &&
                prepared["floor"]?.jsonPrimitive?.intOrNull != null && price != null && price > 0 && balance != null && balance >= price &&
                context["refresh_price"] == JsonPrimitive(price) && context["balance_before"] == JsonPrimitive(balance) &&
                context["currency"] == JsonPrimitive("alpha") && context["attempt_key"]?.jsonPrimitive?.contentOrNull?.let {
                    it.matches(Regex("[a-zA-Z0-9_-]+")) && id(prepared) == "refresh-prepare-$it"
                } == true &&
                prepared["verification_status"] == JsonPrimitive("prepared_only") &&
                prepared["source"] == JsonPrimitive("automation_action")) { "刷新 prepared 原图或实际双读无效" }
            val prior = events.take(prepared["seq"]!!.jsonPrimitive.int - 1)
            val anchor = checkNotNull(prior.lastOrNull(::isAnchor)) { "刷新缺少原余额锚点" }
            check(anchor["event_id"] == context["anchor_id"] && store.evidenceValid(run, anchor) &&
                anchor["node_id"] == prepared["node_id"] && anchor["floor"] == prepared["floor"] &&
                payload(anchor)["balance_after"] == context["balance_before"]) { "刷新原余额锚点不符" }
            var dispatched: JsonObject? = null
            var rejected = false
            var settled: JsonObject? = null
            events.filter { payload(it)["prepared_id"] == prepared["event_id"] && type(it) in REFRESH_RESULTS }.forEach { event ->
                check(event["seq"]!!.jsonPrimitive.int > prepared["seq"]!!.jsonPrimitive.int && !rejected && settled == null &&
                    event["node_id"] == prepared["node_id"] && event["floor"] == prepared["floor"] &&
                    payload(event)["attempt_key"] == context["attempt_key"] && store.evidenceValid(run, event)) { "刷新结果次序、关联或原图无效" }
                if (type(event) == "shop.refresh_settled") {
                    val after = payload(event)["balance_after"]?.jsonPrimitive?.longOrNull
                    val preceding = events.take(event["seq"]!!.jsonPrimitive.int - 1)
                    check(after != null && event["verification_status"] == JsonPrimitive("verified") &&
                        event["source"] == JsonPrimitive("actual_screen") &&
                        savedRead(payload(event)["balance_read"])?.let(::readAmount) == after &&
                        payload(event)["balance_before"] == context["balance_before"] && payload(event)["spent"] == context["refresh_price"] &&
                        payload(event)["anchor_id"] == context["anchor_id"] && payload(event)["currency"] == JsonPrimitive("alpha") &&
                        payload(event)["interval_event_ids"] == JsonArray(refreshInterval(prepared, preceding).map { it["event_id"]!! }) &&
                        canSettleRefresh(run, prepared, preceding, after, event["node_id"]!!.jsonPrimitive.long,
                            event["floor"]!!.jsonPrimitive.int)) { "刷新 settled 缺少严格单一扣费区间证明" }
                    settled = event
                } else {
                    check(event["source"] == JsonPrimitive("automation_action") && event["evidence_ref"] == prepared["evidence_ref"] &&
                        event["evidence_sha256"] == prepared["evidence_sha256"] &&
                        payload(event)["refresh_price"] == context["refresh_price"] && payload(event)["balance_before"] == context["balance_before"]) {
                        "刷新执行结果原始上下文不符"
                    }
                    if (type(event) == "shop.refresh_rejected") {
                        check(dispatched == null && event["verification_status"] == JsonPrimitive("rejected_not_executed") &&
                            payload(event)["result"] == JsonPrimitive("not_executed")) { "刷新拒绝不能证明未执行" }
                        rejected = true
                    } else {
                        check(dispatched == null && event["verification_status"] == JsonPrimitive("dispatched_only")) { "刷新重复派发" }
                        dispatched = event
                    }
                }
            }
            RefreshState(prepared, dispatched, rejected, settled)
        }
        check(states.map { payload(it.prepared)["attempt_key"] }.distinct().size == states.size &&
            states.count { !it.rejected && it.settled == null } <= 1) { "存在重复或多个未决刷新 attempt" }
        events.filter { type(it) in REFRESH_RESULTS && (type(it) != "shop.refresh_dispatched" || payload(it)["prepared_id"] != null) }.forEach { event ->
            check(states.any { it.prepared["event_id"] == payload(event)["prepared_id"] }) { "刷新结果缺少精确 prepared" }
        }
        var pending: JsonElement? = null
        events.filter { type(it) == "shop.refresh_prepared" || type(it) in REFRESH_RESULTS && payload(it)["prepared_id"] != null }.forEach { event ->
            if (type(event) == "shop.refresh_prepared") {
                check(pending == null) { "未决刷新之前不能准备另一次刷新" }
                pending = event["event_id"]
            } else {
                check(pending == payload(event)["prepared_id"]) { "刷新结果不属于当前单一未决区间" }
                if (type(event) != "shop.refresh_dispatched") pending = null
            }
        }
        return states
    }

    private fun refreshInterval(prepared: JsonObject, events: List<JsonObject>): List<JsonObject> =
        events.dropWhile { it["event_id"] != payload(prepared)["anchor_id"] }.drop(1)

    private fun canSettleRefresh(run: LabyrinthShoppingJournal.Run, prepared: JsonObject, events: List<JsonObject>,
        balance: Long, nodeId: Long?, floor: Int?): Boolean {
        val context = payload(prepared)
        val before = context["balance_before"]?.jsonPrimitive?.longOrNull ?: return false
        val price = context["refresh_price"]?.jsonPrimitive?.longOrNull ?: return false
        return price > 0 && before - balance == price && singleRefreshInterval(run, prepared, events, nodeId, floor)
    }

    private fun singleRefreshInterval(run: LabyrinthShoppingJournal.Run, prepared: JsonObject,
        events: List<JsonObject>, nodeId: Long?, floor: Int?): Boolean {
        val released = rejectedRefreshEventIds(events)
        val interval = refreshInterval(prepared, events).filter { id(it) !in released }
        return prepared["node_id"] == JsonPrimitive(nodeId) && prepared["floor"] == JsonPrimitive(floor) &&
            interval.count { type(it) == "shop.refresh_prepared" } == 1 &&
            interval.all { type(it) in NON_FINANCIAL || type(it) in DIAGNOSTIC || it == prepared ||
                type(it) == "shop.refresh_dispatched" && payload(it)["prepared_id"] == prepared["event_id"] && store.evidenceValid(run, it) }
    }

    /**
     * 未决刷新期间对不上的余额读数只做诊断，不写成锚点。
     *
     * 写成锚点会永久破坏结算区间；完全不记又会丢掉「当时屏幕上到底读到什么」的证据。
     */
    private fun recordPendingRefreshDiagnostic(run: LabyrinthShoppingJournal.Run, prepared: JsonObject,
        balance: Long, nodeId: Long?, floor: Int?, now: Long, text: String?, evidenceId: Long,
        encode: (FileOutputStream) -> Boolean) {
        val key = "wallet-pending-" + LabyrinthShoppingJournal.key(
            "${prepared["event_id"]?.jsonPrimitive?.contentOrNull}|$balance|$nodeId|$floor",
        )
        if (store.read(run).any { id(it) == key }) return
        val evidence = store.evidence(run, key, encode)
        store.append(run, key, "currency.observed_pending_refresh", nodeId, floor, now, buildJsonObject {
            put("prepared_id", prepared["event_id"]!!)
            put("attempt_key", payload(prepared)["attempt_key"]!!)
            put("balance_before", payload(prepared)["balance_before"]!!)
            put("refresh_price", payload(prepared)["refresh_price"]!!)
            put("balance_observed", balance)
            put("attribution", "diagnostic_pending_refresh")
            put("ocr_text", text); put("ocr_evidence_id", JsonPrimitive(evidenceId))
        }, evidence)
    }

    private fun rejectedRefreshEventIds(events: List<JsonObject>): Set<String> {
        val rejectedIds = events.filter { type(it) == "shop.refresh_rejected" }.map { payload(it)["prepared_id"] }.toSet()
        return events.filter { type(it) == "shop.refresh_prepared" && it["event_id"] in rejectedIds ||
            type(it) == "shop.refresh_rejected" }.map(::id).toSet()
    }

    private fun savedRead(value: JsonElement?): LabyrinthShopReceiptRead? {
        val read = value as? JsonObject ?: return null
        return LabyrinthShopReceiptRead(read["text"]?.jsonPrimitive?.contentOrNull,
            read["evidence_id"]?.jsonPrimitive?.longOrNull ?: return null,
            read["confirmations"]?.jsonPrimitive?.intOrNull ?: return null)
    }

    private fun readAmount(read: LabyrinthShopReceiptRead): Long? = read.takeIf { it.confirmations >= 2 }?.text
        ?.let(LabyrinthShopPurchaseReceiptObservation::parseAmount)

    private fun readEvidence(read: LabyrinthShopReceiptRead) = buildJsonObject {
        put("text", read.text); put("evidence_id", read.evidenceId); put("confirmations", read.confirmations)
    }

    internal fun lastAnchor(run: LabyrinthShoppingJournal.Run): JsonObject? = synchronized(store.lock) {
        store.read(run).lastOrNull(::isAnchor)?.also {
            check(store.evidenceValid(run, it)) { "余额锚点原图校验失败" }
        }
    }

    private fun isAnchor(event: JsonObject): Boolean = type(event) in BALANCE_TYPES &&
        payload(event)["balance_after"]?.jsonPrimitive?.longOrNull?.let { it >= 0 } == true

    companion object {
        private val REFRESH_RESULTS = setOf("shop.refresh_dispatched", "shop.refresh_rejected", "shop.refresh_settled")
        private val BALANCE_TYPES = setOf("currency.observed", "shop.purchase_completed", "shop.refreshed", "shop.refresh_settled", "battle.reward", "event.reward")
        private val FINANCIAL_SOURCES = setOf("shop.refresh_dispatched", "battle.completed", "event.choice_dispatched")
        private val NON_FINANCIAL = setOf("shop.purchase_intent", "shop.purchase_intent_cancelled", "shop.ownership_committed")
        /** 未决刷新期间对不上的读数：留证据，但不参与归因、不当锚点、不阻断后续结算。 */
        private val DIAGNOSTIC = setOf("currency.observed_pending_refresh")
    }
}
