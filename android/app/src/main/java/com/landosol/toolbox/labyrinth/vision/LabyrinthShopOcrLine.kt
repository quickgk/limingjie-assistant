package com.landosol.toolbox.labyrinth.vision

/** 购物OCR文本及裁图内的位置，与战果识别完全独立。 */
data class LabyrinthShopOcrLine(val text: String, val rect: EntryPixelRect?)

/** 失效只撤销逻辑证据，仍由原回调释放实际在途请求。调用方持有锁。 */
internal class LabyrinthShopOcrEpoch {
    data class Ticket(val epoch: Long, val fingerprint: Long, val evidenceId: Long)
    private var fingerprint: Long? = null
    private var epoch = 0L
    private var sequence = 0L
    private var pending: Ticket? = null

    fun observe(value: Long): Boolean {
        if (fingerprint == value) return false
        fingerprint = value
        epoch++
        return true
    }

    fun clear() {
        fingerprint = null
        epoch++
    }

    fun request(): Ticket? {
        val current = fingerprint ?: return null
        if (pending != null) return null
        return Ticket(epoch, current, ++sequence).also { pending = it }
    }

    fun complete(ticket: Ticket): Boolean {
        if (pending != ticket) return false
        pending = null
        return ticket.epoch == epoch && ticket.fingerprint == fingerprint
    }
}
