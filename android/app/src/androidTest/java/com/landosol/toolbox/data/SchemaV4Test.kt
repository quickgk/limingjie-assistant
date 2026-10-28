package com.landosol.toolbox.data

import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.landosol.toolbox.data.local.AppDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 逐段与整链的 schema 迁移断言。
 *
 * 这里保留 v3→v4 的单段断言与 v1→v5 的整链断言；v4→v5 的数据保留与幂等语义由
 * [Migration4To5Test] 覆盖，不在本类重复。
 */
@RunWith(AndroidJUnit4::class)
class SchemaV4Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun schemaV3MigratesToV4() {
        helper.createDatabase(TEST_DATABASE, 3).close()
        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            4,
            true,
            AppDatabase.MIGRATION_3_4,
        ).close()
    }

    @Test
    fun schemaV1MigratesToV5() {
        helper.createDatabase(TEST_DATABASE, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            5,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
        ).close()
    }

    private companion object {
        const val TEST_DATABASE = "schema-v4-migration-test.db"
    }
}
