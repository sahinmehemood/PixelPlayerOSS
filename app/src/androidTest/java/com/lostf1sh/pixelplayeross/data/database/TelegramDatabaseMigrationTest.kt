package com.lostf1sh.pixelplayeross.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelegramDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = PixelPlayerDatabase::class.java,
    )

    @Test
    fun migrationSixToSevenAddsTelegramTablesWithoutTouchingExistingData() {
        migrationHelper.createDatabase(DATABASE_NAME, 6).use { database ->
            database.execSQL(
                "CREATE TABLE migration_sentinel (id INTEGER NOT NULL PRIMARY KEY, value TEXT NOT NULL)"
            )
            database.execSQL(
                "INSERT INTO migration_sentinel (id, value) VALUES (1, 'keep-me')"
            )

            MIGRATION_6_7.migrate(database)

            assertThat(database.tableExists("telegram_channels")).isTrue()
            assertThat(database.tableExists("telegram_songs")).isTrue()
            assertThat(database.tableExists("telegram_topics")).isTrue()
            assertThat(database.tableExists("songs")).isTrue()
            assertThat(database.tableExists("migration_sentinel")).isTrue()

            database.query("SELECT value FROM migration_sentinel WHERE id = 1").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("keep-me")
            }

            database.execSQL(
                """
                    INSERT INTO telegram_channels
                    (chat_id, title, username, is_channel, is_saved, song_count, last_sync_time)
                    VALUES (-100123, 'Test Channel', 'test_channel', 1, 1, 1, 1700000000)
                """.trimIndent()
            )
            database.execSQL(
                """
                    INSERT INTO telegram_songs
                    (id, chat_id, message_id, file_id, title, artist, duration, file_size, mime_type, date_added)
                    VALUES ('telegram:-100123:456', -100123, 456, 77, 'Track', 'Artist', 1000, 4096, 'audio/mpeg', 1700000000)
                """.trimIndent()
            )

            database.query("SELECT title FROM telegram_songs WHERE chat_id = -100123").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("Track")
            }
        }
    }

    private fun android.database.sqlite.SQLiteDatabase.tableExists(table: String): Boolean {
        query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
            null,
            null,
            null,
            null,
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private companion object {
        const val DATABASE_NAME = "telegram-database-migration-test"
    }
}
