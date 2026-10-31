package com.landosol.toolbox.labyrinth.vision

/**
 * 只验证弹窗标题，不使用共用蓝色外框或底层商品文字。
 *
 * 标题条两侧的花纹在二值化后可能读成竖线一类的字形，取词时按「去掉首尾非文字符号」归一；
 * 带问号的标题属于别的提示，仍然拒绝。
 */
internal fun labyrinthShopCompletionHeading(text: String?): Boolean {
    val value = text?.filterNot(Char::isWhitespace) ?: return false
    if ('？' in value || '?' in value) return false
    return value.trim { !it.isLetterOrDigit() } in setOf("购买完毕", "购买完成")
}
