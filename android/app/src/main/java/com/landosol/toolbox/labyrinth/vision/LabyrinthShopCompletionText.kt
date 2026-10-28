package com.landosol.toolbox.labyrinth.vision

/** 只验证弹窗标题，不使用共用蓝色外框或底层商品文字。 */
internal fun labyrinthShopCompletionHeading(text: String?): Boolean =
    text?.filterNot(Char::isWhitespace) in setOf("购买完毕", "购买完成")
