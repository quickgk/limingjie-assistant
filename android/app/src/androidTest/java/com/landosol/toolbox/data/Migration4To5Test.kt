package com.landosol.toolbox.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.labyrinth.RoomLabyrinthRunStateStore
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationOnlyAddsOwnershipTableAndIndexAndPreservesEveryExistingRecord() {
        val old = helper.createDatabase(TEST_DATABASE, 4)
        seedV4(old)
        val recordsBefore = RECORD_TABLES.associateWith { rows(old, "SELECT * FROM `$it`") }
        val schemaBefore = existingSchema(old)
        old.close()

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE, 5, true, AppDatabase.MIGRATION_4_5)
        try {
            RECORD_TABLES.forEach { table ->
                assertEquals(table, recordsBefore.getValue(table), rows(migrated, "SELECT * FROM `$table`"))
            }
            assertEquals(schemaBefore, existingSchema(migrated))
            assertTrue(rows(migrated, "SELECT * FROM labyrinth_shop_ownership").isEmpty())
            assertEquals(
                listOf(listOf("index_labyrinth_shop_ownership_run_key_committed_at")),
                rows(migrated, "SELECT name FROM sqlite_master WHERE name = 'index_labyrinth_shop_ownership_run_key_committed_at'"),
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun roomOpensV4WithMigrationAndNewReceiptRemainsIdempotentAfterReopen() = runBlocking {
        helper.createDatabase(TEST_DATABASE, 4).also {
            seedV4(it)
            it.close()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        val database = openDatabase()
        val committed = try {
            val store = RoomLabyrinthRunStateStore(database)
            val before = store.load(1L)!!
            assertEquals(3, before.observedRelics.single().observationCount)
            assertEquals(listOf("1209"), before.joinedCharacters.map { it.characterId })
            store.recordShopPurchase(1L, RECEIPT, "SHOP_RECEIPT", 500L, listOf(relic())).also { snapshot ->
                assertEquals(4, snapshot.observedRelics.single().observationCount)
                assertEquals(before.joinedCharacters, snapshot.joinedCharacters)
                assertEquals(2, snapshot.unrecognizedCharacterCount)
                assertTrue(database.labyrinthRunStateDao().hasReceipt("account:1", RECEIPT))
            }
        } finally {
            database.close()
        }

        val reopened = openDatabase()
        try {
            assertEquals(
                committed,
                RoomLabyrinthRunStateStore(reopened).recordShopPurchase(1L, RECEIPT, "RETRY", 600L, listOf(relic())),
            )
        } finally {
            reopened.close()
        }
    }

    private fun seedV4(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO accounts VALUES (1, 'saved-account', 'cn-bilibili', 'uid-1', 'credential-1', 1, 10, 20)")
        db.execSQL("INSERT INTO feature_profiles VALUES (1, 1, 'saved-profile', 'labyrinth', '{\"enabled\":true}', 10, 20)")
        db.execSQL("INSERT INTO automation_runs VALUES (1, 1, 'labyrinth', 'ACTIVE', 10, NULL, NULL, 'saved-summary')")
        db.execSQL("INSERT INTO labyrinth_routes VALUES (1, 1, 1, 7, 2, 3, '{\"route\":[1]}', 20)")
        db.execSQL("INSERT INTO labyrinth_reroll_checkpoints VALUES (1, 3, 7, 42, 2, '{\"policy\":1}', 'ACTIVE', 'saved-message', 30)")
        db.execSQL("INSERT INTO labyrinth_run_states VALUES ('account:1', 1, 'ACTIVE', 'RELIC_ACQUIRED', 2, 10, 100)")
        db.execSQL("INSERT INTO labyrinth_run_characters VALUES ('account:1', '1209', '伊莉亚(新年)', 0, 0.93, 10, 100)")
        db.execSQL("INSERT INTO labyrinth_run_relics VALUES ('account:1', '14004', '测试遗物', '强化', '1', 'saved-effect', 0.93, 10, 100, 3)")
        db.execSQL("INSERT INTO migration_checkpoints VALUES ('saved-checkpoint', 100, 'saved-hash')")
    }

    private fun existingSchema(db: SupportSQLiteDatabase) = rows(
        db,
        "SELECT type, name, tbl_name, sql FROM sqlite_master " +
            "WHERE type IN ('table', 'index') AND tbl_name != 'labyrinth_shop_ownership' ORDER BY type, name",
    )

    private fun rows(db: SupportSQLiteDatabase, query: String): List<List<String?>> = db.query(query).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).map { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                })
            }
        }
    }

    private fun relic() = LabyrinthRelicMatch(
        slotId = "shop_1",
        relicId = "14004",
        displayName = "测试遗物",
        attribute = "强化",
        attributeBonus = "1",
        effect = "saved-effect",
        confidence = 0.93,
        screenRect = EntryPixelRect(0, 0, 10, 10),
        iconRect = EntryPixelRect(0, 0, 10, 10),
    )

    private companion object {
        const val TEST_DATABASE = "migration-v4-v5-test.db"
        const val RECEIPT = "account:1:enter:7:shop:1"
        val RECORD_TABLES = listOf(
            "accounts", "feature_profiles", "automation_runs", "labyrinth_routes", "labyrinth_reroll_checkpoints",
            "labyrinth_run_states", "labyrinth_run_characters", "labyrinth_run_relics", "migration_checkpoints",
        )
    }
}
