package com.landosol.toolbox.labyrinth.vision

import java.util.concurrent.atomic.AtomicBoolean

/** 提交失败和推理异常均完成一次缺读；回调异常不能重发或伪造第二次读数。 */
internal class ShopTextReadDispatch<T>(
    private val execute: (Runnable) -> Unit,
    private val recognize: (T) -> String?,
) {
    fun submit(input: T, callback: (String?) -> Unit) {
        val completed = AtomicBoolean(false)
        fun finish(text: String?) {
            if (completed.compareAndSet(false, true)) runCatching { callback(text) }
        }
        try {
            execute(Runnable { finish(runCatching { recognize(input) }.getOrNull()) })
        } catch (_: Exception) {
            finish(null)
        }
    }
}
