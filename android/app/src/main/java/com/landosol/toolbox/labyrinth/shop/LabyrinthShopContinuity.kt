package com.landosol.toolbox.labyrinth.shop

/** Volatile continuity is independent of screenshot generations and durable financial evidence. */
class LabyrinthShopContinuity {
    private var scope: String? = null
    private var cycle: Int = 0
    private var continuousStock = false
    private var balanceVerified = false
    var successfulPurchases: Int = 0; private set

    @Synchronized fun bind(value: String, cycleStart: Int, purchases: Int, recovered: Boolean = false) {
        require(purchases >= 0)
        if (scope != value) {
            scope = value
            cycle = cycleStart
            continuousStock = !recovered && purchases == 0
            balanceVerified = false
        } else if (cycle != cycleStart) {
            cycle = cycleStart
            continuousStock = true
        }
        if (recovered) {
            continuousStock = false
            balanceVerified = false
        }
        successfulPurchases = purchases
        if (purchases > 6) continuousStock = false
    }

    @Synchronized fun invalidate() {
        continuousStock = false
        balanceVerified = false
    }

    @Synchronized fun rejectStock() { continuousStock = false }
    @Synchronized fun requireBalanceVerification() { balanceVerified = false }
    @Synchronized fun verifiedWallet() { if (scope != null) balanceVerified = true }
    @Synchronized fun canReuseBalance(value: String): Boolean = scope == value && balanceVerified
    @Synchronized fun remainingSingleScreen(): Int? =
        if (continuousStock && successfulPurchases in 3..6) 6 - successfulPurchases else null

    @Synchronized fun reset() {
        scope = null; cycle = 0; successfulPurchases = 0
        continuousStock = false; balanceVerified = false
    }
}
