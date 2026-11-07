package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.shop.LabyrinthShopReceiptLedger.Companion.id
import com.landosol.toolbox.labyrinth.shop.LabyrinthShopReceiptLedger.Companion.payload
import com.landosol.toolbox.labyrinth.shop.LabyrinthShopReceiptLedger.Companion.pendingIntent
import com.landosol.toolbox.labyrinth.shop.LabyrinthShopReceiptLedger.Companion.purchaseAbandoned
import com.landosol.toolbox.labyrinth.shop.LabyrinthShopReceiptLedger.Companion.type
import com.landosol.toolbox.labyrinth.vision.LabyrinthCurrencyObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemText
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopPurchaseReceiptObservation
import java.util.UUID
import kotlinx.serialization.json.*

data class ReceiptCommit(val key: String, val itemId: String?, val automated: Boolean, val ownershipCommitted: Boolean)

data class PendingOwnership(val commit: ReceiptCommit, val itemName: String, val nodeId: Long?, val floor: Int?)

/** Session 的购物耐久事实入口；Room 实际持有事务由调用方独立提交。 */
class LabyrinthShoppingModule(private val store: LabyrinthShoppingJournal) {
    private var run: LabyrinthShoppingJournal.Run? = null
    private val receipts = LabyrinthShopReceiptLedger(store)
    private val balances = LabyrinthShopBalanceLedger(store)

    fun open(accountId: Long?, enterId: Long, batchRunId: String?, now: Long): Unit = synchronized(store.lock) {
        run = null
        run = store.open(accountId, enterId, batchRunId, now)
    }

    fun reset(): Unit = synchronized(store.lock) { run = null }

    val active: Boolean get() = synchronized(store.lock) { run != null }
    val enterId: Long? get() = synchronized(store.lock) { run?.enterId }
    val verifiedBalance: Long? get() = synchronized(store.lock) { run?.let(balances::verifiedBalance) }

    fun ownsJournal(journal: LabyrinthShoppingJournal): Boolean = journal === store

    fun events(): List<JsonObject> = synchronized(store.lock) { run?.let(store::read).orEmpty() }

    fun pendingOwnership(): List<PendingOwnership> = synchronized(store.lock) {
        val current = run ?: return@synchronized emptyList()
        val events = store.read(current)
        events.filter { type(it) == "shop.purchase_completed" &&
            payload(it)["automated"]?.jsonPrimitive?.booleanOrNull == true &&
            !purchaseAbandoned(events, payload(it)["intent_id"]) }.mapNotNull { event ->
            val commit = receipts.commit(current, event, events)
            val intent = events.single { type(it) == "shop.purchase_intent" &&
                it["event_id"] == payload(event)["intent_id"] }
            val context = payload(intent)
            val name = checkNotNull(payload(event)["item_name"]?.jsonPrimitive?.contentOrNull)
            // 用归一比较，不用 ==：同一次购买里，商品卡与回执的 OCR 可能一个读「櫻」一个读「樱」，
            // 而回执提交阶段用的是 LabyrinthShopItemName.equal，这里必须同口径。
            check(LabyrinthShopItemName.equal(context["item_name"]?.jsonPrimitive?.contentOrNull, name)) {
                "恢复持有精确名称不一致（意图=${context["item_name"]}，回执=$name）"
            }
            if (commit.itemId == null) {
                val label = checkNotNull(LabyrinthShopItemText.roleImprintLabel(name)) { "恢复空持有缺少精确印记身份" }
                check(context["item_kind"] == JsonPrimitive("role_imprint") && context["item_id"] == JsonNull &&
                    context["role_label"] == JsonPrimitive(label) &&
                    context["choice_imprint"] == JsonPrimitive(LabyrinthShopItemText.isChoiceRoleImprint(name))) {
                    "恢复印记职能或选择类型不一致"
                }
            } else check(commit.itemId.isNotBlank() && context["item_kind"] == JsonPrimitive("relic") &&
                context["role_label"] == JsonNull && context["choice_imprint"] == JsonPrimitive(false) &&
                !LabyrinthShopItemText.looksLikeRoleImprint(name)) { "恢复持有缺少精确遗物身份" }
            if (commit.ownershipCommitted) return@mapNotNull null
            PendingOwnership(commit, name,
                event["node_id"]?.jsonPrimitive?.longOrNull, event["floor"]?.jsonPrimitive?.intOrNull)
        }
    }

    fun observeBalance(observation: LabyrinthCurrencyObservation?, nodeId: Long?, floor: Int?, now: Long): Unit = synchronized(store.lock) {
        val current = currentRun()
        val balance = observation?.balance ?: return@synchronized
        balances.observe(current, balance, nodeId, floor, now, observation.read?.text, observation.read?.evidenceId,
            checkNotNull(observation.read).confirmations)
    }

    /** A queued first button must own a newly created id, not reuse another pending operation. */
    fun recordNewIntent(item: LabyrinthShopItemMatch, balance: Long, nodeId: Long?, floor: Int?, now: Long): String = synchronized(store.lock) {
        check(pendingIntent(store.read(currentRun())) == null) { "已有购买意图，不能创建另一首按钮动作" }
        recordIntent(item, balance, nodeId, floor, now)
    }

    fun recordIntent(item: LabyrinthShopItemMatch, balance: Long, nodeId: Long?, floor: Int?, now: Long): String = synchronized(store.lock) {
        val current = currentRun()
        require(balance >= 0) { "购买前余额不能为负" }
        check(item.purchasable) { "商品尚未核实为可购买" }
        val price = checkNotNull(item.price?.takeIf { it > 0 }) { "商品必须有已知的上架计算价格" }
        check(balance >= price) { "购买前余额不足" }
        val isRole = item.kind == LabyrinthShopItemKind.ROLE_IMPRINT
        val name = checkNotNull(if (isRole) item.titleText else item.relicMatch?.displayName) { "商品精确身份未核实" }
        check(name.isNotBlank()) { "商品精确身份未核实" }
        val label = if (isRole) item.roleImprintLabel else null
        if (isRole) {
            check(label != null && LabyrinthShopItemText.roleImprintLabel(name) == label &&
                LabyrinthShopItemText.isChoiceRoleImprint(name) == item.choiceRoleImprint && item.relicMatch?.relicId == null) {
                "职能印记名称、职能或选择类型不一致"
            }
        } else check(item.relicMatch?.relicId?.isNotBlank() == true && item.roleImprintLabel == null &&
            !LabyrinthShopItemText.looksLikeRoleImprint(name) &&
            !LabyrinthShopItemText.looksLikeRoleImprint(item.titleText)) { "遗物身份与商品分类冲突" }
        val context = buildJsonObject {
            put("item_id", if (isRole) JsonNull else JsonPrimitive(item.relicMatch?.relicId))
            put("item_name", name.filterNot(Char::isWhitespace)); put("role_label", JsonPrimitive(label))
            put("choice_imprint", item.choiceRoleImprint); put("slot_id", item.slotId)
            put("item_kind", if (isRole) "role_imprint" else "relic")
            put("expected_spent", price); put("expected_balance_before", balance)
        }
        val events = store.read(current)
        pendingIntent(events)?.let { pending ->
            receipts.validateIntent(current, pending)
            val previous = payload(pending)
            check(pending["node_id"] == JsonPrimitive(nodeId) && pending["floor"] == JsonPrimitive(floor) &&
                context.all { (key, value) -> previous[key] == value }) {
                "未结束意图的节点、商品、价格或余额发生变化，必须先取消"
            }
            balances.lastAnchor(current)?.let { anchor ->
                check(payload(anchor)["balance_after"]?.jsonPrimitive?.longOrNull == balance) {
                    "待确认意图之后已观察到不同余额，必须先取消意图"
                }
            }
            return@synchronized id(pending)
        }
        requireCurrentBalance(current, balance)
        val key = "intent-${UUID.randomUUID()}"
        store.append(current, key, "shop.purchase_intent", nodeId, floor, now, JsonObject(context + buildJsonObject {
            put("price_source", "catalog")
            put("title_evidence_id", JsonPrimitive(item.titleEvidenceId))
        }), verification = "intent_only", source = "automation_intent")
        key
    }

    /** 仅返回 true 的调用可进入真实 executor；false 表示结果未知或已派发，绝不能再次点击。 */
    fun prepareConfirmation(now: Long, expectedIntentId: String): Boolean = synchronized(store.lock) {
        val current = currentRun()
        val events = store.read(current)
        val intent = checkNotNull(pendingIntent(events)) { "没有可确认的未取消购买意图" }
        check(id(intent) == expectedIntentId) { "当前核验的商品不属于待确认意图" }
        val state = receipts.confirmation(current, intent, events)
        if (state.prepared != null || state.dispatched != null) return@synchronized false
        val balance = checkNotNull(payload(intent)["expected_balance_before"]?.jsonPrimitive?.longOrNull)
        requireCurrentBalance(current, balance)
        val attempt = events.count { type(it) == "shop.purchase_confirm_prepared" &&
            payload(it)["intent_id"] == intent["event_id"] } + 1
        store.append(current, "prepare-${id(intent)}-$attempt", "shop.purchase_confirm_prepared",
            intent["node_id"]?.jsonPrimitive?.longOrNull, intent["floor"]?.jsonPrimitive?.intOrNull, now,
            buildJsonObject { put("intent_id", intent["event_id"]!!) },
            verification = "prepared_only", source = "automation_action")
        true
    }

    /** 只能由 executor 确定未执行的结果调用；异常、超时和未知结果必须保留 prepared。 */
    fun rejectConfirmation(now: Long, expectedIntentId: String? = null): Unit = synchronized(store.lock) {
        val current = currentRun()
        val events = store.read(current)
        if (expectedIntentId != null && purchaseAbandoned(events, JsonPrimitive(expectedIntentId))) return@synchronized
        val intent = checkNotNull(pendingIntent(events)) { "没有可撤回的购买确认" }
        check(expectedIntentId == null || id(intent) == expectedIntentId) { "拒绝回调不属于当前购买意图" }
        val state = receipts.confirmation(current, intent, events)
        check(state.dispatched == null) { "已派发确认不能冒充未执行" }
        val prepared = state.prepared
        if (prepared == null) {
            check(events.any { type(it) == "shop.purchase_confirm_rejected" &&
                payload(it)["intent_id"] == intent["event_id"] }) { "没有可撤回的 prepared" }
            return@synchronized
        }
        store.append(current, "reject-${id(prepared)}", "shop.purchase_confirm_rejected",
            intent["node_id"]?.jsonPrimitive?.longOrNull, intent["floor"]?.jsonPrimitive?.intOrNull, now,
            buildJsonObject {
                put("intent_id", intent["event_id"]!!); put("prepared_id", prepared["event_id"]!!)
                put("result", "not_executed")
            },
            verification = "rejected_not_executed", source = "automation_action")
    }

    /** 兼容旧调用；新流程必须先 prepare 落盘。回执已证明完成时不再追加迟到 dispatch。 */
    fun confirmDispatched(now: Long = System.currentTimeMillis(), expectedIntentId: String? = null): Unit = synchronized(store.lock) {
        val current = currentRun()
        val events = store.read(current)
        if (expectedIntentId != null && purchaseAbandoned(events, JsonPrimitive(expectedIntentId))) return@synchronized
        val intent = pendingIntent(events) ?: run {
            val latest = checkNotNull(events.lastOrNull { type(it) == "shop.purchase_intent" &&
                (expectedIntentId == null || id(it) == expectedIntentId) }) {
                "没有可确认的购买意图"
            }
            if (purchaseAbandoned(events, latest["event_id"])) return@synchronized
            val completed = checkNotNull(events.singleOrNull { type(it) == "shop.purchase_completed" &&
                payload(it)["intent_id"] == latest["event_id"] }) { "没有可确认的未取消购买意图" }
            check(receipts.commit(current, completed, events).automated) { "人工回执不能由迟到派发升级" }
            return@synchronized
        }
        check(expectedIntentId == null || id(intent) == expectedIntentId) { "派发回调不属于当前购买意图" }
        val state = receipts.confirmation(current, intent, events)
        if (state.dispatched != null) return@synchronized
        check(state.prepared != null || events.none { type(it) == "shop.purchase_confirm_prepared" &&
            payload(it)["intent_id"] == intent["event_id"] }) { "prepared 已撤回，必须重新 prepare" }
        store.append(current, "confirm-${id(intent)}", "shop.purchase_confirm_dispatched",
            intent["node_id"]?.jsonPrimitive?.longOrNull, intent["floor"]?.jsonPrimitive?.intOrNull, now,
            buildJsonObject {
                put("intent_id", intent["event_id"]!!)
                state.prepared?.let { put("prepared_id", it["event_id"]!!) }
            },
            verification = "dispatched_only", source = "automation_action")
    }

    /** Only for a first-button action known never to have reached its executor. */
    fun cancelUnexecutedIntent(expectedIntentId: String, now: Long): Boolean = synchronized(store.lock) {
        val current = run ?: return@synchronized false
        val events = store.read(current)
        val intent = pendingIntent(events)?.takeIf { id(it) == expectedIntentId } ?: return@synchronized false
        // Even a historical prepared attempt is outside this narrow first-button rollback.
        if (events.any { payload(it)["intent_id"] == intent["event_id"] && type(it) in setOf(
                "shop.purchase_confirm_prepared", "shop.purchase_confirm_dispatched", "shop.purchase_completed") }) return@synchronized false
        cancelIntent(now)
        true
    }

    fun cancelIntent(now: Long): Unit = synchronized(store.lock) {
        val current = currentRun()
        val events = store.read(current)
        val intent = pendingIntent(events) ?: return@synchronized
        val confirmation = receipts.confirmation(current, intent, events)
        check(confirmation.prepared == null && confirmation.dispatched == null) {
            "确认结果未知或已执行，必须先保存确定未执行的拒绝，不能取消意图"
        }
        store.append(current, "cancel-${id(intent)}", "shop.purchase_intent_cancelled",
            intent["node_id"]?.jsonPrimitive?.longOrNull, intent["floor"]?.jsonPrimitive?.intOrNull, now,
            buildJsonObject { put("intent_id", intent["event_id"]!!) },
            verification = "cancelled", source = "automation_action")
    }

    /** 只结束指定购买流程的等待，不断言未执行，也不补写回执或持有。 */
    fun abandonPurchase(intentId: String, reason: String, now: Long): Unit = synchronized(store.lock) {
        require(reason.isNotBlank()) { "放弃原因不能为空" }
        val current = currentRun()
        val events = store.read(current)
        val intent = checkNotNull(events.singleOrNull { type(it) == "shop.purchase_intent" && id(it) == intentId }) {
            "放弃购买缺少当前运行的精确意图"
        }
        if (purchaseAbandoned(events, intent["event_id"]) || events.any {
                type(it) == "shop.purchase_intent_cancelled" && payload(it)["intent_id"] == intent["event_id"] }) return@synchronized
        val receipt = events.singleOrNull { type(it) == "shop.purchase_completed" && payload(it)["intent_id"] == intent["event_id"] }
        if (receipt != null && events.any { type(it) == "shop.ownership_committed" &&
                payload(it)["receipt_id"] == receipt["event_id"] }) return@synchronized
        store.append(current, "abandon-$intentId", "shop.purchase_abandoned",
            intent["node_id"]?.jsonPrimitive?.longOrNull, intent["floor"]?.jsonPrimitive?.intOrNull, now,
            buildJsonObject {
                put("intent_id", intent["event_id"]!!); put("receipt_id", receipt?.get("event_id") ?: JsonNull)
                put("reason", reason)
            }, verification = "abandoned", source = "automation_action")
    }

    fun abandonRefresh(refreshId: String, reason: String, now: Long): Unit = synchronized(store.lock) {
        balances.abandonRefresh(currentRun(), refreshId, reason, now)
    }

    fun acceptReceipt(receipt: LabyrinthShopPurchaseReceiptObservation, nodeId: Long?, floor: Int?, now: Long): ReceiptCommit = synchronized(store.lock) {
        receipts.record(currentRun(), receipt, nodeId, floor, now)
    }

    fun markOwnershipCommitted(key: String, now: Long): Unit = synchronized(store.lock) {
        receipts.markOwnershipCommitted(currentRun(), key, now)
    }

    /** true 才可进入真实刷新确认 executor；同 attempt 的重试绝不重复点击。 */
    fun prepareRefresh(attemptKey: String, price: Long, observation: LabyrinthCurrencyObservation,
        nodeId: Long, floor: Int, now: Long): Boolean = synchronized(store.lock) {
        balances.prepareRefresh(currentRun(), attemptKey, price, observation, nodeId, floor, now)
    }

    fun rejectRefresh(attemptKey: String, now: Long): Unit = synchronized(store.lock) {
        balances.refreshResult(currentRun(), attemptKey, executed = false, now)
    }

    fun refreshDispatched(attemptKey: String, now: Long): Unit = synchronized(store.lock) {
        balances.refreshResult(currentRun(), attemptKey, executed = true, now)
    }

    fun pendingRefresh(): JsonObject? = synchronized(store.lock) {
        val current = currentRun()
        val events = store.read(current)
        balances.refreshStates(current, events).singleOrNull { it.pending }?.prepared
            ?: events.lastOrNull { event -> type(event) == "shop.refresh_dispatched" && payload(event)["prepared_id"] == null &&
                events.none { type(it) == "shop.refreshed" &&
                    payload(it)["interval_event_ids"]?.jsonArray?.contains(event["event_id"]) == true ||
                    type(it) == "shop.refresh_abandoned" && payload(it)["refresh_id"] == event["event_id"] } }
    }

    fun refreshCount(nodeId: Long?, floor: Int?): Int = synchronized(store.lock) {
        val current = currentRun()
        val events = store.read(current)
        val preparedCount = balances.refreshStates(current, events).count { !it.rejected &&
            it.prepared["node_id"] == JsonPrimitive(nodeId) && it.prepared["floor"] == JsonPrimitive(floor) }
        preparedCount + events.count { type(it) == "shop.refresh_dispatched" && payload(it)["prepared_id"] == null &&
            it["node_id"] == JsonPrimitive(nodeId) && it["floor"] == JsonPrimitive(floor) }
    }

    fun successfulRefreshCount(nodeId: Long?, floor: Int?): Int = synchronized(store.lock) {
        val current = currentRun()
        val events = store.read(current)
        balances.refreshStates(current, events)
        events.filter { type(it) in setOf("shop.refresh_settled", "shop.refreshed") &&
            it["node_id"] == JsonPrimitive(nodeId) && it["floor"] == JsonPrimitive(floor) }.map(::id).distinct().size
    }

    data class InventorySummary(
        val cycleStart: Int,
        val purchases: Int,
        val relicPurchases: Int,
        val purchasedSlots: Set<String>?,
    )

    fun inventorySummary(nodeId: Long?, floor: Int?): InventorySummary = synchronized(store.lock) {
        val current = currentRun()
        val events = store.read(current)
        balances.refreshStates(current, events)
        val cycle = events.lastOrNull { type(it) in setOf("shop.refresh_settled", "shop.refreshed") &&
            it["node_id"] == JsonPrimitive(nodeId) && it["floor"] == JsonPrimitive(floor) }
            ?.get("seq")?.jsonPrimitive?.intOrNull ?: 0
        val owned = events.filter { type(it) == "shop.ownership_committed" }
            .mapNotNull { payload(it)["receipt_id"]?.jsonPrimitive?.contentOrNull }.toSet()
        val completed = events.filter { type(it) == "shop.purchase_completed" && id(it) in owned &&
            it["node_id"] == JsonPrimitive(nodeId) && it["floor"] == JsonPrimitive(floor) &&
            it["seq"]!!.jsonPrimitive.int > cycle }
            .distinctBy { receipts.commit(current, it, events).key }
        val slots = completed.map { receipt ->
            val intent = events.singleOrNull { type(it) == "shop.purchase_intent" &&
                it["event_id"] == payload(receipt)["intent_id"] }
            intent?.takeIf { payload(receipt)["automated"] == JsonPrimitive(true) &&
                it["seq"]!!.jsonPrimitive.int > cycle }
                ?.let { payload(it)["slot_id"]?.jsonPrimitive?.contentOrNull }
        }
        InventorySummary(cycle, completed.size,
            completed.count { payload(it)["item_id"]?.jsonPrimitive?.contentOrNull != null },
            slots.filterNotNull().toSet().takeIf { set -> set.size == completed.size &&
                set.all { it in com.landosol.toolbox.labyrinth.vision.LabyrinthShopSlots.keys } })
    }

    fun successfulPurchaseCount(nodeId: Long?, floor: Int?, relicsOnly: Boolean = false): Int =
        inventorySummary(nodeId, floor).let { if (relicsOnly) it.relicPurchases else it.purchases }

    fun verifiedBalance(nodeId: Long?, floor: Int?): Long? = synchronized(store.lock) {
        val current = currentRun()
        val events = store.read(current)
        val balance = balances.verifiedBalance(current, events) ?: return@synchronized null
        val anchor = balances.lastAnchor(current, events) ?: return@synchronized null
        balance.takeIf { anchor["node_id"] == JsonPrimitive(nodeId) && anchor["floor"] == JsonPrimitive(floor) }
    }

    fun refreshCycleStart(nodeId: Long?, floor: Int?): Int = synchronized(store.lock) {
        val current = currentRun()
        val events = store.read(current)
        balances.refreshStates(current, events)
        events.lastOrNull { type(it) in setOf("shop.refresh_settled", "shop.refreshed") &&
            it["node_id"] == JsonPrimitive(nodeId) && it["floor"] == JsonPrimitive(floor) }?.get("seq")?.jsonPrimitive?.intOrNull ?: 0
    }

    /** 兼容旧 API；Session 必须使用确认前落盘的 prepareRefresh 协议。 */
    fun recordRefresh(price: Long, balance: Long, nodeId: Long?, floor: Int?, now: Long): Unit = synchronized(store.lock) {
        val current = currentRun()
        require(price > 0 && balance >= price) { "刷新实际价格或前余额无效" }
        val key = "refresh-" + LabyrinthShoppingJournal.key("$nodeId|$floor|$price|$balance|$now")
        val events = store.read(current)
        events.firstOrNull { id(it) == key }?.let {
            check(type(it) == "shop.refresh_dispatched" && it["node_id"] == JsonPrimitive(nodeId) &&
                it["floor"] == JsonPrimitive(floor) && payload(it)["refresh_price"] == JsonPrimitive(price) &&
                payload(it)["balance_before"] == JsonPrimitive(balance) &&
                it["source"] == JsonPrimitive("automation_action") && it["verification_status"] == JsonPrimitive("dispatched_only")) {
                "重复刷新的金额、作用域或来源不符"
            }
            return@synchronized
        }
        check(pendingIntent(events) == null) { "购买意图未结束时不能刷新商店" }
        requireCurrentBalance(current, balance)
        store.append(current, key, "shop.refresh_dispatched", nodeId, floor, now, buildJsonObject {
            put("refresh_price", price); put("balance_before", balance); put("currency", "alpha")
        }, verification = "dispatched_only", source = "automation_action")
    }

    fun canDismiss(receipt: LabyrinthShopPurchaseReceiptObservation, nodeId: Long?, floor: Int?,
        expectedReceiptId: String? = null): Boolean = synchronized(store.lock) {
        run?.let { receipts.canDismiss(it, receipt, nodeId, floor, expectedReceiptId) } ?: false
    }

    private fun currentRun(): LabyrinthShoppingJournal.Run = checkNotNull(run) { "购物模块尚未 open" }

    private fun requireCurrentBalance(current: LabyrinthShoppingJournal.Run, balance: Long) {
        if (balances.lastAnchor(current) != null || store.read(current).any {
                type(it) in setOf("shop.purchase_abandoned", "shop.refresh_abandoned") }) check(balances.verifiedBalance(current) == balance) {
            "调用方余额与耐久核实余额不一致，或上次已派发操作尚未对账"
        }
    }
}
