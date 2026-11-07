package com.landosol.toolbox.labyrinth.shop

import org.junit.Assert.*
import org.junit.Test

class LabyrinthShopContinuityTest {
    @Test fun `all goods count but snapshot invalidation does not reset a stock round`() {
        val state = LabyrinthShopContinuity()
        state.bind("run-shop-pricing", 0, 0)
        state.bind("run-shop-pricing", 0, 2)
        assertNull(state.remainingSingleScreen())
        state.bind("run-shop-pricing", 0, 3)
        assertEquals(3, state.remainingSingleScreen())
        state.verifiedWallet()
        assertTrue(state.canReuseBalance("run-shop-pricing"))
        state.bind("run-shop-pricing", 0, 4)
        assertEquals(2, state.remainingSingleScreen())
        assertTrue(state.canReuseBalance("run-shop-pricing"))
    }

    @Test fun `recovery takeover and contradictory stock require full coverage and actual balance`() {
        for (invalidate in listOf<(LabyrinthShopContinuity) -> Unit>({ it.invalidate() }, { it.rejectStock() })) {
            val state = LabyrinthShopContinuity()
            state.bind("shop", 0, 0); state.bind("shop", 0, 3); state.verifiedWallet()
            invalidate(state)
            state.bind("shop", 0, 3)
            assertNull(state.remainingSingleScreen())
        }
        val recovered = LabyrinthShopContinuity()
        recovered.bind("shop", 0, 3, recovered = true)
        assertNull(recovered.remainingSingleScreen())
        assertFalse(recovered.canReuseBalance("shop"))
        recovered.verifiedWallet()
        assertTrue(recovered.canReuseBalance("shop"))
        assertFalse(recovered.canReuseBalance("other-shop"))
    }

    @Test fun `recovery on an already bound scope cannot retain financial or stock trust`() {
        val state = LabyrinthShopContinuity()
        state.bind("shop", 0, 3); state.verifiedWallet()
        state.bind("shop", 0, 3, recovered = true)
        assertNull(state.remainingSingleScreen())
        assertFalse(state.canReuseBalance("shop"))
    }

    @Test fun `only successful refresh or a new shop starts a new round`() {
        val state = LabyrinthShopContinuity()
        state.bind("shop", 0, 0); state.bind("shop", 0, 3)
        state.invalidate(); state.bind("shop", 0, 3)
        assertNull(state.remainingSingleScreen())
        state.bind("shop", 20, 0); state.bind("shop", 20, 3)
        assertEquals(3, state.remainingSingleScreen())
        state.bind("other-shop", 0, 0)
        assertEquals(0, state.successfulPurchases)
        assertFalse(state.canReuseBalance("other-shop"))
    }
}
