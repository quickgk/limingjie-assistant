package com.landosol.toolbox.labyrinth.shop

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopReceiptRead
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemText
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopPurchaseReceiptObservation
import java.io.FileOutputStream
import kotlinx.serialization.json.*

/** 金额及原图先入账，持有提交是之后独立的耐久事实。 */
class LabyrinthShopReceiptLedger(private val store: LabyrinthShoppingJournal) {
    fun record(run: LabyrinthShoppingJournal.Run, receipt: LabyrinthShopPurchaseReceiptObservation,
        nodeId: Long?, floor: Int?, now: Long, encode: (FileOutputStream) -> Boolean): ReceiptCommit = synchronized(store.lock) {
        check(receipt.verified) { "购买回执尚未通过独立双读、金额与确认按钮校验" }
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
            check(store.evidenceValid(run, intent)) { "购买意图原图缺失或校验失败" }
            check(intent["node_id"] == JsonPrimitive(nodeId) && intent["floor"] == JsonPrimitive(floor)) {
                "回执与待确认意图不在同一节点或楼层，必须先取消意图"
            }
            check(matches(intent, receipt)) { "回执商品、价格或前余额与购买意图不一致" }
        }
        val confirmation = intent?.let { confirmation(run, it, events) }
        val action = confirmation?.dispatched ?: confirmation?.prepared
        val evidence = store.evidence(run, key, encode)
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
        }, evidence)
        commit(run, event, events + event)
    }

    fun markOwnershipCommitted(run: LabyrinthShoppingJournal.Run, key: String, now: Long): Unit = synchronized(store.lock) {
        val events = store.read(run)
        val receipt = events.singleOrNull { id(it) == key && type(it) == "shop.purchase_completed" }
        check(receipt != null) { "持有提交必须在购买回执入账之后" }
        val saved = commit(run, receipt, events)
        if (saved.ownershipCommitted) return@synchronized
        store.append(run, "ownership-$key", "shop.ownership_committed",
            receipt["node_id"]?.jsonPrimitive?.longOrNull, receipt["floor"]?.jsonPrimitive?.intOrNull, now,
            buildJsonObject { put("receipt_id", key); put("item_id", JsonPrimitive(saved.itemId)) },
            receipt["evidence_ref"]?.jsonPrimitive?.contentOrNull,
            source = "ownership_commit")
    }

    fun canDismiss(run: LabyrinthShoppingJournal.Run, receipt: LabyrinthShopPurchaseReceiptObservation): Boolean = synchronized(store.lock) {
        if (!receipt.verified) return@synchronized false
        val events = store.read(run)
        val event = events.singleOrNull { type(it) == "shop.purchase_completed" &&
            payload(it)["receipt_fingerprint"]?.jsonPrimitive?.content == fingerprint(receipt) }
            ?: return@synchronized false
        // 原图不通过时只允许保留弹窗；不能由重识别或新图修补旧事实。
        runCatching { commit(run, event, events).let { it.automated && it.ownershipCommitted } }.getOrDefault(false)
    }

    internal fun commit(run: LabyrinthShoppingJournal.Run, event: JsonObject, events: List<JsonObject>): ReceiptCommit {
        check(store.evidenceValid(run, event)) { "已记录购买回执的原图缺失或校验失败" }
        val context = payload(event)
        validateReceipt(event)
        val automated = context["automated"]?.jsonPrimitive?.booleanOrNull == true
        val intentId = context["intent_id"]?.jsonPrimitive?.contentOrNull
        val intent = intentId?.let { key -> events.singleOrNull { type(it) == "shop.purchase_intent" && id(it) == key } }
        val receiptSeq = event["seq"]!!.jsonPrimitive.int
        if (intentId != null) {
            check(intent != null && store.evidenceValid(run, intent)) { "关联购买意图的原图无效" }
            check(intent["seq"]!!.jsonPrimitive.int < receiptSeq &&
                intent["node_id"] == event["node_id"] && intent["floor"] == event["floor"] &&
                context["item_id"] == payload(intent)["item_id"] && matches(intent,
                    context["item_name"]?.jsonPrimitive?.contentOrNull,
                    context["spent"]?.jsonPrimitive?.longOrNull, context["balance_before"]?.jsonPrimitive?.longOrNull) &&
                events.none { type(it) == "shop.purchase_intent_cancelled" && payload(it)["intent_id"] == context["intent_id"] &&
                    it["seq"]!!.jsonPrimitive.int < receiptSeq }) { "回执关联意图已取消、内容不符或次序无效" }
        }
        if (automated) {
            check(intent != null) { "自动购买缺少精确意图" }
            // 即使没有 dispatch 回调，未撤回的耐久 prepared 与精确回执也证明实际完成。
            val state = confirmation(run, intent, events)
            val action = state.dispatched ?: state.prepared
            check(action != null && action["seq"]!!.jsonPrimitive.int < receiptSeq) {
                "自动购买缺少回执之前未撤回的确认耐久证据"
            }
            context["confirmation_id"]?.let { check(it == action["event_id"]) { "回执确认编号不一致" } }
        }
        val ownerships = events.filter { type(it) == "shop.ownership_committed" &&
            payload(it)["receipt_id"] == event["event_id"] }
        check(ownerships.size <= 1) { "同一回执存在多个持有提交事件" }
        val ownership = ownerships.singleOrNull()
        if (ownership != null) check(ownership["seq"]!!.jsonPrimitive.int > receiptSeq &&
            ownership["node_id"] == event["node_id"] && ownership["floor"] == event["floor"] &&
            payload(ownership)["item_id"] == context["item_id"] &&
            ownership["evidence_ref"] == event["evidence_ref"] && store.evidenceValid(run, ownership)) {
            "持有提交次序、身份或证据无效"
        }
        return ReceiptCommit(id(event), context["item_id"]?.jsonPrimitive?.contentOrNull, automated, ownership != null)
    }

    internal data class Confirmation(val prepared: JsonObject?, val dispatched: JsonObject?)

    internal fun confirmation(run: LabyrinthShoppingJournal.Run, intent: JsonObject,
        events: List<JsonObject>): Confirmation {
        check(store.evidenceValid(run, intent) &&
            intent["verification_status"]?.jsonPrimitive?.contentOrNull == "intent_only" &&
            intent["source"]?.jsonPrimitive?.contentOrNull == "automation_intent") { "购买意图耐久证据无效" }
        var prepared: JsonObject? = null
        var dispatched: JsonObject? = null
        events.filter { type(it) in CONFIRMATION_TYPES && payload(it)["intent_id"] == intent["event_id"] }.forEach { event ->
            val seq = event["seq"]!!.jsonPrimitive.int
            check(seq > intent["seq"]!!.jsonPrimitive.int && event["node_id"] == intent["node_id"] &&
                event["floor"] == intent["floor"] && event["evidence_ref"] == intent["evidence_ref"] &&
                event["evidence_sha256"] == intent["evidence_sha256"] && store.evidenceValid(run, event) &&
                event["source"]?.jsonPrimitive?.contentOrNull == "automation_action" && events.none {
                    type(it) in setOf("shop.purchase_intent_cancelled", "shop.purchase_completed") &&
                        payload(it)["intent_id"] == intent["event_id"] && it["seq"]!!.jsonPrimitive.int < seq
                }) { "确认事件的意图、节点、原图或耐久次序无效" }
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
        // 入账时已验证按钮矩形；恢复只核对已保存独立读数和原图，不重新授权屏幕点击。
        val receipt = LabyrinthShopPurchaseReceiptObservation(read("heading"), read("item"), read("expenditure"),
            read("before"), read("after"), read("button"))
        val spent = receipt.spent
        val before = receipt.before
        check(type(event) == "shop.purchase_completed" && receipt.titleConfirmed && receipt.independentReadsConfirmed &&
            receipt.itemName != null && spent != null && spent > 0 && before != null && before >= spent &&
            before - spent == receipt.after && receipt.button?.text?.filterNot(Char::isWhitespace) == "确认" &&
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

        internal fun pendingIntent(events: List<JsonObject>): JsonObject? {
            val pending = events.filter { candidate -> type(candidate) == "shop.purchase_intent" && events.none {
                type(it) in setOf("shop.purchase_intent_cancelled", "shop.purchase_completed") &&
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
            } else context["item_name"]?.jsonPrimitive?.contentOrNull?.filterNot(Char::isWhitespace) == name
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
