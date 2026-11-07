package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingDarkTextCropTest {
    @Test fun `深灰完整字形保留且彩色分隔线和背景排除`() {
        assertEquals(0xff000000.toInt(), shoppingDarkTextPixel(0xff333333.toInt()))
        assertEquals(0xff000000.toInt(), shoppingDarkTextPixel(0xffc7b0b0.toInt()))
        assertEquals(0xffffffff.toInt(), shoppingDarkTextPixel(0xff64a5dc.toInt()))
        assertEquals(0xffffffff.toInt(), shoppingDarkTextPixel(0xffffffff.toInt()))
        assertEquals(0xffffffff.toInt(), shoppingDarkTextPixel(0xffdddddd.toInt()))
    }

    @Test fun `固定色阈值不随计划名改变也不放行近似字`() {
        assertEquals(0xff000000.toInt(), shoppingDarkTextPixel(0xffc7a7a7.toInt()))
        assertEquals(0xffffffff.toInt(), shoppingDarkTextPixel(0xffc7a6a6.toInt()))
        assertEquals(0xffffffff.toInt(), shoppingDarkTextPixel(0xffc8c8c8.toInt()))
        assertEquals(EntryReferenceRect(560, 345, 758, 60), LABYRINTH_SHOP_CONFIRMATION_TITLE_RECT)
        org.junit.Assert.assertFalse(com.landosol.toolbox.labyrinth.shop.LabyrinthShopItemName.equal("暴击弧弓", "暴击弧引"))
    }
}
