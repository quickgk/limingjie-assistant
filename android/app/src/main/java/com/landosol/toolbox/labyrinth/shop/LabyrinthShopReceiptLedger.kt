package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopReceiptRead
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemText
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopPurchaseReceiptObservation
import kotlinx.serialization.json.*

/** 精确回执事实先入账，持有提交是之后独立的耐久事实。 */
class LabyrinthShopReceiptLedger(private val store: LabyrinthShoppingJournal) {
    fun record(run: LabyrinthShoppingJournal.Run, receipt: LabyrinthShopPurchaseReceiptObservation,
        nodeId: Long?, floor: Int?, now: Long): ReceiptCommit = synchronized(store.lock) {
        check(receipt.verified) { "购买回执尚未通过独立双读与金额校验" }
        val events = store.read(run)
        val key = "purchase-" + LabyrinthShoppingJournal.key("${run.runId}|$nodeId|${fingerprint(receipt)}")
        events.firstOrNull { id(it) == key }?.let {
            check(type(it) == "shop.purchase_completed") { "回执编号被其他事件占用" }
            check(it["node_id"] == JsonPrimitive(nodeId) && it["floor"] == JsonPrimitive(floor) &&
                payload(it)["receipt_fingerprint"] == JsonPrimitive(fingerprint(receipt))) { "重复回执的节点或楼层证据不一致" }
            return@synchronized commit(run, it, events)
        }
        val intent = pendingIntent(events)
        if (intent != null) {
            validateIntent(run, intent)
            check(intent["node_id"] == JsonPrimitive(nodeId) && intent["floor"] == JsonPrimitive(floor)) {
                "回执与待确认意图不在同一节点或楼层，必须先取消意图"
            }
            check(matches(intent, receipt)) { "回执商品、价格或前余额与购买意图不一致" }
        }
        val confirmation = intent?.let { confirmation(run, it, events) }
        val action = confirmation?.dispatched ?: confirmation?.prepared
        val event = store.append(run, key, "shop.purchase_completed", nodeId, floor, now, buildJsonObject {
            put("receipt_id", key); put("receipt_fingerprint", fingerprint(receipt))
            put("intent_id", intent?.get("event_id") ?: JsonNull)
            put("item_name", receipt.itemName); put("item_id", intent?.let { payload(it)["item_id"] } ?: JsonNull)
            put("item_kind", if (LabyrinthShopItemText.looksLikeRoleImprint(receipt.itemName)) "role_imprint" else "relic")
            put("quantity", 1); put("currency", "alpha"); put("spent", JsonPrimitive(receipt.spent))
            put("balance_before", JsonPrimitive(receipt.before)); put("balance_after", JsonPrimitive(receipt.after))
            put("association_status", if (intent == null) "observed_without_intent" else "intent_matched")
            put("automated", action != null); put("confirmation_id", action?.get("event_id") ?: JsonNull)
            put("ocr", receiptEvidence(receipt))
        })
        commit(run, event, events + event)
    }

    fun markOwnershipCommitted(run: LabyrinthShoppingJournal.Run, key: String, now: Long): Unit = synchronized(store.lock) {
        val events = store.read(run)
        val receipt = events.singleOrNull { id(it) == key && type(it) == "shop.purchase_completed" }
        check(receipt != null) { "持有提交必须在购买回执入账之后" }
        check(!purchaseAbandoned(events, payload(receipt)["intent_id"])) { "已放弃购买不能补写持有提交" }
        val saved = commit(run, receipt, events)
        if (saved.ownershipCommitted) return@synchronized
        store.append(run, "ownership-$key", "shop.ownership_committed",
            receipt["node_id"]?.jsonPrimitive?.longOrNull, receipt["floor"]?.jsonPrimitive?.intOrNull, now,
            buildJsonObject { put("receipt_id", key); put("item_id", JsonPrimitive(saved.itemId)) },
            source = "ownership_commit")
    }

    fun canDismiss(run: LabyrinthShoppingJournal.Run, receipt: LabyrinthShopPurchaseReceiptObservation,
        nodeId: Long?, floor: Int?, expectedReceiptId: String? = null): Boolean = synchronized(store.lock) {
        if (!receipt.verified || nodeId == null || floor == null) return@synchronized false
        runCatching {
            val events = store.read(run)
            val key = "purchase-" + LabyrinthShoppingJournal.key("${run.runId}|$nodeId|${fingerprint(receipt)}")
            if (expectedReceiptId != null && expectedReceiptId != key) return@runCatching false
            val event = events.singleOrNull { type(it) == "shop.purchase_completed" && id(it) == key &&
                it["node_id"] == JsonPrimitive(nodeId) && it["floor"] == JsonPrimitive(floor) &&
                payload(it)["receipt_fingerprint"] == JsonPrimitive(fingerprint(receipt)) } ?: return@runCatching false
            if (purchaseAbandoned(events, payload(event)["intent_id"])) return@runCatching false
            commit(run, event, events).let { it.automated && it.ownershipCommitted }
        }.getOrDefault(false)
    }

    internal fun commit(run: LabyrinthShoppingJournal.Run, event: JsonObject, events: List<JsonObject>): ReceiptCommit {
        checkScope(run, event)
        val context = payload(event)
        validateReceipt(event)
        val automated = checkNotNull(context["automated"]?.jsonPrimitive?.booleanOrNull) { "回执缺少自动归属状态" }
        val intentId = context["intent_id"]?.jsonPrimitive?.contentOrNull
        val intent = intentId?.let { key -> events.singleOrNull { type(it) == "shop.purchase_intent" && id(it) == key } }
        val receiptSeq = event["seq"]!!.jsonPrimitive.int
        check(context["association_status"] == JsonPrimitive(if (intentId == null) "observed_without_intent" else "intent_matched")) {
            "回执归属状态与意图关联不符"
        }
        if (intentId != null) {
            checkNotNull(intent) { "缺少关联购买意图" }
            validateIntent(run, intent)
            val original = payload(intent)
            check(intent["seq"]!!.jsonPrimitive.int < receiptSeq &&
                intent["node_id"] == event["node_id"] && intent["floor"] == event["floor"] &&
                context["item_kind"] == original["item_kind"] && context["item_id"] == original["item_id"] &&
                LabyrinthShopItemName.equal(original["item_name"]?.jsonPrimitive?.contentOrNull,
                    context["item_name"]?.jsonPrimitive?.contentOrNull) && matches(intent,
                    context["item_name"]?.jsonPrimitive?.contentOrNull,
                    context["spent"]?.jsonPrimitive?.longOrNull, context["balance_before"]?.jsonPrimitive?.longOrNull) &&
                events.none { type(it) in setOf("shop.purchase_intent_cancelled", "shop.purchase_abandoned") &&
                    payload(it)["intent_id"] == context["intent_id"] && it["seq"]!!.jsonPrimitive.int < receiptSeq }) {
                "回执关联意图已取消或放弃、内容不符或次序无效"
            }
        }
        if (automated) {
            check(intent != null) { "自动购买缺少精确意图" }
            // 即使没有 dispatch 回调，未撤回的耐久 prepared 与精确回执也证明实际完成。
            val state = confirmation(run, intent, events)
            val action = state.dispatched ?: state.prepared
            check(action != null && action["seq"]!!.jsonPrimitive.int < receiptSeq &&
                context["confirmation_id"] == action["event_id"]) { "回执缺少此前精确且未撤回的确认" }
        } else check(context["confirmation_id"] == JsonNull) { "人工回执不能携带自动确认许可" }
        val ownerships = events.filter { type(it) == "shop.ownership_committed" && payload(it)["receipt_id"] == event["event_id"] }
        check(ownerships.size <= 1) { "同一回执存在多个持有提交事件" }
        val ownership = ownerships.singleOrNull()
        if (ownership != null) {
            checkScope(run, ownership)
            check(ownership["seq"]!!.jsonPrimitive.int > receiptSeq &&
                ownership["node_id"] == event["node_id"] && ownership["floor"] == event["floor"] &&
                payload(ownership)["item_id"] == context["item_id"] &&
                ownership["source"] == JsonPrimitive("ownership_commit") &&
                ownership["verification_status"] == JsonPrimitive("verified")) { "持有提交次序、身份或来源无效" }
        }
        return ReceiptCommit(id(event), context["item_id"]?.jsonPrimitive?.contentOrNull, automated, ownership != null)
    }

    private fun checkScope(run: LabyrinthShoppingJournal.Run, event: JsonObject) {
        check(event["run_id"] == JsonPrimitive(run.runId) && event["enter_id"] == JsonPrimitive(run.enterId)) { "购物事实不属于当前运行" }
    }

    internal fun validateIntent(run: LabyrinthShoppingJournal.Run, intent: JsonObject) {
        checkScope(run, intent)
        val context = payload(intent)
        val name = context["item_name"]?.jsonPrimitive?.contentOrNull
        val price = context["expected_spent"]?.jsonPrimitive?.longOrNull
        val balance = context["expected_balance_before"]?.jsonPrimitive?.longOrNull
        val choice = context["choice_imprint"]?.jsonPrimitive?.booleanOrNull
        check(type(intent) == "shop.purchase_intent" && intent["source"] == JsonPrimitive("automation_intent") &&
            intent["verification_status"] == JsonPrimitive("intent_only") && !name.isNullOrBlank() &&
            price != null && price > 0 && balance != null && balance >= price && choice != null) { "购买意图身份、金额或来源无效" }
        if (context["item_kind"] == JsonPrimitive("role_imprint")) {
            val label = LabyrinthShopItemText.roleImprintLabel(name)
            check(label != null && context["item_id"] == JsonNull && context["role_label"] == JsonPrimitive(label) &&
                LabyrinthShopItemText.isChoiceRoleImprint(name) == choice) { "印记意图职能、选择类型或持有身份不符" }
        } else check(context["item_kind"] == JsonPrimitive("relic") &&
            !context["item_id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() && context["role_label"] == JsonNull &&
            !choice && !LabyrinthShopItemText.looksLikeRoleImprint(name)) { "遗物意图分类或身份不符" }
    }

    internal data class Confirmation(val prepared: JsonObject?, val dispatched: JsonObject?)

    internal fun confirmation(run: LabyrinthShoppingJournal.Run, intent: JsonObject,
        events: List<JsonObject>): Confirmation {
        validateIntent(run, intent)
        var prepared: JsonObject? = null
        var dispatched: JsonObject? = null
        events.filter { type(it) in CONFIRMATION_TYPES && payload(it)["intent_id"] == intent["event_id"] }.forEach { event ->
            val seq = event["seq"]!!.jsonPrimitive.int
            check(seq > intent["seq"]!!.jsonPrimitive.int && event["node_id"] == intent["node_id"] &&
                event["floor"] == intent["floor"] && event["run_id"] == intent["run_id"] && event["enter_id"] == intent["enter_id"] &&
                event["source"]?.jsonPrimitive?.contentOrNull == "automation_action" && events.none {
                    type(it) in setOf("shop.purchase_intent_cancelled", "shop.purchase_abandoned", "shop.purchase_completed") &&
                        payload(it)["intent_id"] == intent["event_id"] && it["seq"]!!.jsonPrimitive.int < seq
                }) { "确认事件的意图、作用域、来源或耐久次序无效" }
            when (type(event)) {
                "shop.purchase_confirm_prepared" -> {
                    check(prepared == null && dispatched == null &&
                        event["verification_status"]?.jsonPrimitive?.contentOrNull == "prepared_only") {
                        "未知确认结果不能再次 prepare"
                    }
                    prepared = event
                }
                "shop.purchase_confirm_dispatched" -> {
                    check(dispatched == null && event["verification_status"]?.jsonPrimitive?.contentOrNull == "dispatched_only" &&
                        (prepared == null && payload(event)["prepared_id"] == null && events.none {
                            type(it) == "shop.purchase_confirm_prepared" && payload(it)["intent_id"] == intent["event_id"] &&
                                it["seq"]!!.jsonPrimitive.int < seq
                        } ||
                            prepared != null && payload(event)["prepared_id"] == prepared!!["event_id"])) {
                        "确认派发重复或未关联当前 prepared"
                    }
                    dispatched = event
                }
                "shop.purchase_confirm_rejected" -> {
                    check(prepared != null && dispatched == null &&
                        payload(event)["prepared_id"] == prepared!!["event_id"] &&
                        payload(event)["result"]?.jsonPrimitive?.contentOrNull == "not_executed" &&
                        event["verification_status"]?.jsonPrimitive?.contentOrNull == "rejected_not_executed") {
                        "只有确定未执行且精确关联的耐久拒绝才能撤回 prepared"
                    }
                    prepared = null
                }
            }
        }
        return Confirmation(prepared, dispatched)
    }

    private fun validateReceipt(event: JsonObject) {
        val context = payload(event)
        val ocr = context["ocr"]?.jsonObject
        fun read(key: String): LabyrinthShopReceiptRead? {
            val saved = ocr?.get(key)?.jsonObject ?: return null
            return LabyrinthShopReceiptRead(saved["text"]?.jsonPrimitive?.contentOrNull,
                saved["evidence_id"]?.jsonPrimitive?.longOrNull ?: return null,
                saved["confirmations"]?.jsonPrimitive?.intOrNull ?: return null)
        }
        // 恢复核对已保存的独立读数、身份与金额算术。
        val receipt = LabyrinthShopPurchaseReceiptObservation(read("heading"), read("item"), read("expenditure"),
            read("before"), read("after"), read("button"))
        val spent = receipt.spent
        val before = receipt.before
        check(type(event) == "shop.purchase_completed" && receipt.titleConfirmed && receipt.independentReadsConfirmed &&
            receipt.itemName != null && spent != null && spent > 0 && before != null && before >= spent &&
            before - spent == receipt.after &&
            event["verification_status"]?.jsonPrimitive?.contentOrNull == "verified" &&
            event["source"]?.jsonPrimitive?.contentOrNull == "actual_screen" &&
            context["receipt_id"] == event["event_id"] &&
            context["receipt_fingerprint"] == JsonPrimitive(fingerprint(receipt)) &&
            context["item_name"] == JsonPrimitive(receipt.itemName) &&
            context["spent"] == JsonPrimitive(receipt.spent) &&
            context["balance_before"] == JsonPrimitive(receipt.before) &&
            context["balance_after"] == JsonPrimitive(receipt.after) &&
            context["quantity"] == JsonPrimitive(1) && context["currency"] == JsonPrimitive("alpha") &&
            context["item_kind"] == JsonPrimitive(if (LabyrinthShopItemText.looksLikeRoleImprint(receipt.itemName))
                "role_imprint" else "relic")) { "已保存回执的独立读数、商品或金额证据不一致" }
    }

    companion object {
        internal val CONFIRMATION_TYPES = setOf("shop.purchase_confirm_prepared", "shop.purchase_confirm_dispatched",
            "shop.purchase_confirm_rejected")
        internal fun id(event: JsonObject): String = event["event_id"]!!.jsonPrimitive.content
        internal fun type(event: JsonObject): String? = event["type"]?.jsonPrimitive?.contentOrNull
        internal fun payload(event: JsonObject): JsonObject = event["payload"]!!.jsonObject

        internal fun purchaseAbandoned(events: List<JsonObject>, intentId: JsonElement?): Boolean =
            intentId != null && intentId != JsonNull && events.any {
                type(it) == "shop.purchase_abandoned" && payload(it)["intent_id"] == intentId
            }

        internal fun pendingIntent(events: List<JsonObject>): JsonObject? {
            val pending = events.filter { candidate -> type(candidate) == "shop.purchase_intent" && events.none {
                type(it) in setOf("shop.purchase_intent_cancelled", "shop.purchase_abandoned", "shop.purchase_completed") &&
                    payload(it)["intent_id"] == candidate["event_id"]
            } }
            check(pending.size <= 1) { "存在多个未结束购买意图，拒绝猜测自动关联" }
            return pending.singleOrNull()
        }

        internal fun matches(intent: JsonObject, receipt: LabyrinthShopPurchaseReceiptObservation): Boolean =
            matches(intent, receipt.itemName, receipt.spent, receipt.before)

        private fun matches(intent: JsonObject, name: String?, spent: Long?, before: Long?): Boolean {
            val context = payload(intent)
            if (name == null || spent == null || before == null ||
                spent != context["expected_spent"]?.jsonPrimitive?.longOrNull ||
                before != context["expected_balance_before"]?.jsonPrimitive?.longOrNull) return false
            val label = context["role_label"]?.jsonPrimitive?.contentOrNull
            return if (label != null) {
                LabyrinthShopItemText.roleImprintLabel(name) == label &&
                    LabyrinthShopItemText.isChoiceRoleImprint(name) ==
                    (context["choice_imprint"]?.jsonPrimitive?.booleanOrNull == true)
            } else LabyrinthShopItemName.equal(context["item_name"]?.jsonPrimitive?.contentOrNull, name)
        }

        internal fun fingerprint(receipt: LabyrinthShopPurchaseReceiptObservation): String =
            "${receipt.itemName}|${receipt.spent}|${receipt.before}|${receipt.after}"

        fun receiptEvidence(receipt: LabyrinthShopPurchaseReceiptObservation): JsonObject = buildJsonObject {
            listOf("heading" to receipt.heading, "item" to receipt.item, "expenditure" to receipt.expenditure,
                "before" to receipt.balanceBefore, "after" to receipt.balanceAfter, "button" to receipt.button).forEach { (key, read) ->
                put(key, buildJsonObject {
                    put("text", read?.text); put("evidence_id", JsonPrimitive(read?.evidenceId))
                    put("confirmations", JsonPrimitive(read?.confirmations))
                })
            }
        }
    }
}
