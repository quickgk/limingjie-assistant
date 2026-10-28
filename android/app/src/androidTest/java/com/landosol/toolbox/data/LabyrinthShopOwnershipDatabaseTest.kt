package com.landosol.toolbox.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.landosol.toolbox.data.local.AccountEntity
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.data.local.LabyrinthShopOwnershipEntity
import com.landosol.toolbox.labyrinth.LabyrinthRunStateStore
import com.landosol.toolbox.labyrinth.RoomLabyrinthRunStateStore
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LabyrinthShopOwnershipDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var store: LabyrinthRunStateStore

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE)
        openDatabase()
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        store = RoomLabyrinthRunStateStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun sameReceiptDoesNotAddLayersOrChangeSnapshot() = runBlocking {
        val committed = store.recordShopPurchase(null, RECEIPT, "SHOP_RECEIPT", 100L, listOf(relic()))
        val repeated = store.recordShopPurchase(null, RECEIPT, "IGNORED_RETRY", 200L, listOf(relic("12003")))

        assertEquals(committed, repeated)
        assertEquals(1, repeated.observedRelics.single().observationCount)
        assertTrue(database.labyrinthRunStateDao().hasReceipt(RUN_KEY, RECEIPT))
        database.openHelper.writableDatabase.query(
            "SELECT committed_at FROM labyrinth_shop_ownership WHERE run_key = ? AND receipt_id = ?",
            arrayOf(RUN_KEY, RECEIPT),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(100L, cursor.getLong(0))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test
    fun committedReceiptSurvivesStoreAndDatabaseRestartBeforeJournalMarking() = runBlocking {
        val committed = store.recordShopPurchase(null, RECEIPT, "SHOP_RECEIPT", 100L, listOf(relic()))
        val recreatedStore = RoomLabyrinthRunStateStore(database)
        assertEquals(committed, recreatedStore.recordShopPurchase(null, RECEIPT, "RETRY", 200L, listOf(relic())))

        // 只重开 Room，不依赖 journal 的后续成功标记，模拟提交后的进程恢复。
        database.close()
        openDatabase()
        assertEquals(committed, store.recordShopPurchase(null, RECEIPT, "RETRY", 300L, listOf(relic())))
        assertEquals(committed, store.load(null))
    }

    @Test
    fun differentReceiptsForSameRelicAddOneLayerEach() = runBlocking {
        store.recordShopPurchase(null, RECEIPT, "SHOP_RECEIPT", 100L, listOf(relic(), relic()))
        val second = store.recordShopPurchase(null, "$RECEIPT:2", "SHOP_RECEIPT", 200L, listOf(relic()))

        assertEquals(2, second.observedRelics.single().observationCount)
        assertEquals(100L, second.observedRelics.single().firstSeenAt)
        assertEquals(200L, second.observedRelics.single().lastSeenAt)
        assertTrue(database.labyrinthRunStateDao().hasReceipt(RUN_KEY, "$RECEIPT:2"))
        assertEquals(second, store.recordShopPurchase(null, RECEIPT, "RETRY", 300L, listOf(relic())))
    }

    @Test
    fun sameReceiptIsIsolatedAcrossAccountsAndUnboundRun() = runBlocking {
        val firstId = insertAccount("first")
        val secondId = insertAccount("second")
        listOf(firstId, secondId, null).forEach { accountId ->
            val snapshot = store.recordShopPurchase(accountId, RECEIPT, "SHOP_RECEIPT", 100L, listOf(relic()))
            assertEquals(accountId, snapshot.accountId)
            assertEquals(1, snapshot.observedRelics.single().observationCount)
            assertTrue(database.labyrinthRunStateDao().hasReceipt(snapshot.runKey, RECEIPT))
        }
        store.recordShopPurchase(firstId, "$RECEIPT:2", "SHOP_RECEIPT", 200L, listOf(relic()))
        assertEquals(2, store.load(firstId)!!.observedRelics.single().observationCount)
        assertEquals(1, store.load(secondId)!!.observedRelics.single().observationCount)
        assertEquals(1, store.load(null)!!.observedRelics.single().observationCount)
    }

    @Test
    fun purchaseUsesPageMergeWithoutDroppingRosterOrUnknownCount() = runBlocking {
        val before = store.recordPage(
            null, "CHARACTER_JOINED",
            listOf(character("1209", "伊莉亚(新年)"), character(null, null)),
            100L, listOf(relic()),
        )
        val purchased = store.recordShopPurchase(null, RECEIPT, "SHOP_RECEIPT", 200L, listOf(relic()))
        assertEquals(before.joinedCharacters, purchased.joinedCharacters)
        assertEquals(1, purchased.unrecognizedCharacterCount)
        assertEquals(2, purchased.observedRelics.single().observationCount)

        store.begin(null, 300L)
        val latest = store.recordPage(null, "NEXT_PAGE", emptyList(), 400L)
        assertEquals(latest, store.recordShopPurchase(null, RECEIPT, "RETRY", 500L, listOf(relic())))
        assertTrue(database.labyrinthRunStateDao().hasReceipt(RUN_KEY, RECEIPT))
    }

    @Test
    fun receiptInsertFailureRollsBackStateRosterAndRelics() = runBlocking {
        store.recordPage(null, "CHARACTER_JOINED", listOf(character("1209", "伊莉亚(新年)")), 50L)
        val before = store.recordShopPurchase(null, RECEIPT, "SHOP_RECEIPT", 100L, listOf(relic()))
        rejectReceiptInserts()
        try {
            expectConstraintFailure {
                store.recordShopPurchase(null, "$RECEIPT:2", "FAILED_PAGE", 200L, listOf(relic(), relic("12003")))
            }
            assertEquals(before, store.load(null))
            assertTrue(database.labyrinthRunStateDao().hasReceipt(RUN_KEY, RECEIPT))
            assertFalse(database.labyrinthRunStateDao().hasReceipt(RUN_KEY, "$RECEIPT:2"))
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_shop_receipt")
        }
        val retried = store.recordShopPurchase(null, "$RECEIPT:2", "SHOP_RECEIPT", 300L, listOf(relic()))
        assertEquals(2, retried.observedRelics.single().observationCount)
    }

    @Test
    fun receiptInsertFailureDoesNotLeaveNewRunOrOrphanRelics() = runBlocking {
        rejectReceiptInserts()
        try {
            expectConstraintFailure {
                store.recordShopPurchase(null, RECEIPT, "FAILED_PAGE", 100L, listOf(relic()))
            }
            assertNull(store.load(null))
            assertTrue(database.labyrinthRunStateDao().getRelics(RUN_KEY).isEmpty())
            assertFalse(database.labyrinthRunStateDao().hasReceipt(RUN_KEY, RECEIPT))
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_shop_receipt")
        }
        assertEquals(1, store.recordShopPurchase(null, RECEIPT, "SHOP_RECEIPT", 200L, listOf(relic()))
            .observedRelics.single().observationCount)
    }

    @Test
    fun startNewClearsOnlyCurrentRunsReceiptsAndAllowsSameReceiptAgain() = runBlocking {
        val accountId = insertAccount("other")
        val other = store.recordShopPurchase(accountId, RECEIPT, "SHOP_RECEIPT", 100L, listOf(relic()))
        store.recordPage(null, "CHARACTER_JOINED", listOf(character("1209", "伊莉亚(新年)")), 50L)
        store.recordShopPurchase(null, RECEIPT, "SHOP_RECEIPT", 100L, listOf(relic()))
        store.recordShopPurchase(null, "$RECEIPT:2", "SHOP_RECEIPT", 200L, listOf(relic()))

        val fresh = store.startNew(null, 300L)
        assertTrue(fresh.joinedCharacters.isEmpty())
        assertTrue(fresh.observedRelics.isEmpty())
        assertNull(fresh.currentPage)
        assertFalse(database.labyrinthRunStateDao().hasReceipt(RUN_KEY, RECEIPT))
        assertFalse(database.labyrinthRunStateDao().hasReceipt(RUN_KEY, "$RECEIPT:2"))
        assertEquals(other, store.load(accountId))
        assertTrue(database.labyrinthRunStateDao().hasReceipt(other.runKey, RECEIPT))

        database.close()
        openDatabase()
        val purchased = store.recordShopPurchase(null, RECEIPT, "SHOP_RECEIPT", 400L, listOf(relic()))
        assertEquals(1, purchased.observedRelics.single().observationCount)
        assertEquals(400L, purchased.observedRelics.single().firstSeenAt)
    }

    @Test
    fun invalidReceiptIdsFailBeforeAnyMutation() = runBlocking {
        val before = store.begin(null, 50L)
        listOf("", " ", " account:1", "account:1 ", "a/b", "a\\b", "a\n", "a\u0000b", "a'", "a;", "回执", "a".repeat(257))
            .forEach { receiptId ->
                try {
                    store.recordShopPurchase(null, receiptId, "SHOP_RECEIPT", 100L, listOf(relic()))
                    fail("应拒绝不安全的 receiptId")
                } catch (_: IllegalArgumentException) {
                    assertEquals(before, store.load(null))
                }
            }
        val legal = "A0._:-" + "z".repeat(250)
        assertEquals(1, store.recordShopPurchase(null, legal, "SHOP_RECEIPT", 100L, listOf(relic()))
            .observedRelics.single().observationCount)
    }

    @Test
    fun concurrentStoresCommitSameReceiptOnlyOnce() = runBlocking {
        coroutineScope {
            (1..16).map {
                async(Dispatchers.Default) {
                    RoomLabyrinthRunStateStore(database)
                        .recordShopPurchase(null, RECEIPT, "SHOP_RECEIPT", 100L, listOf(relic()))
                }
            }.awaitAll().forEach { snapshot ->
                assertEquals(1, snapshot.observedRelics.single().observationCount)
            }
        }
        assertEquals(1, store.load(null)!!.observedRelics.single().observationCount)
    }

    @Test
    fun receiptPrimaryKeyAbortsDuplicatesAndRunDeletionCascadesMarkers() = runBlocking {
        store.recordShopPurchase(null, RECEIPT, "SHOP_RECEIPT", 100L, listOf(relic()))
        val dao = database.labyrinthRunStateDao()
        expectConstraintFailure { dao.insertReceipt(LabyrinthShopOwnershipEntity(RUN_KEY, RECEIPT, 200L)) }
        assertEquals(1, store.load(null)!!.observedRelics.single().observationCount)
        dao.deleteState(RUN_KEY)
        assertFalse(dao.hasReceipt(RUN_KEY, RECEIPT))
        assertTrue(dao.getRelics(RUN_KEY).isEmpty())
        expectConstraintFailure { dao.insertReceipt(LabyrinthShopOwnershipEntity(RUN_KEY, RECEIPT, 300L)) }
    }

    private fun rejectReceiptInserts() {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_shop_receipt BEFORE INSERT ON labyrinth_shop_ownership
            BEGIN
                SELECT RAISE(ABORT, 'receipt insert failed');
            END
            """.trimIndent(),
        )
    }

    private suspend fun expectConstraintFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("应触发数据库约束异常")
        } catch (_: SQLiteConstraintException) {
            // 事务必须传播失败，不能留下部分持有或回执。
        }
    }

    private suspend fun insertAccount(alias: String): Long = database.accountDao().insert(
        AccountEntity(
            alias = alias,
            serverId = "cn-bilibili",
            gameUid = null,
            credentialKey = "key-$alias",
            isSelected = false,
            createdAt = 1L,
            updatedAt = 1L,
        ),
    )

    private fun character(id: String?, name: String?) = LabyrinthCharacterMatch(
        slotId = "joined",
        characterId = id,
        displayName = name,
        confidence = 0.93,
        screenRect = EntryPixelRect(0, 0, 10, 10),
    )

    private fun relic(id: String = "14004") = LabyrinthRelicMatch(
        slotId = "shop_1",
        relicId = id,
        displayName = "测试遗物$id",
        attribute = "强化",
        attributeBonus = "1",
        effect = "",
        confidence = 0.93,
        screenRect = EntryPixelRect(0, 0, 10, 10),
        iconRect = EntryPixelRect(0, 0, 10, 10),
    )

    private companion object {
        const val TEST_DATABASE = "labyrinth-shop-ownership-test.db"
        const val RUN_KEY = "account:unbound"
        const val RECEIPT = "account:unbound:enter:7:shop:1"
    }
}
