package com.landosol.toolbox.labyrinth.shop

/**
 * 商品名对账：同一个字的繁/日字形与简体写法视为同一个字。
 *
 * 只用于比较，不修改证据里的原文；形近字的猜测仍然禁止——表外字符按原字符比较，
 * 名字差一个笔画就仍然判为不一致。
 */
internal object LabyrinthShopItemName {
    private val VARIANTS: Map<Char, Char> = mapOf(
        '櫻' to '樱', '戰' to '战', '劍' to '剑', '龍' to '龙', '減' to '减', '術' to '术',
        '鎧' to '铠', '襲' to '袭', '裝' to '装', '鍊' to '炼', '護' to '护', '擊' to '击',
        '爾' to '尔', '貝' to '贝', '蘭' to '兰', '獸' to '兽', '聖' to '圣', '靈' to '灵',
        '體' to '体', '屬' to '属', '強' to '强', '鐵' to '铁', '銀' to '银', '銃' to '铳',
    )

    fun equal(expected: String?, actual: String?): Boolean =
        expected != null && actual != null && canonical(expected) == canonical(actual)

    fun canonical(text: String): String =
        text.filterNot(Char::isWhitespace).map { VARIANTS[it] ?: it }.joinToString("")
}
