package com.turnypt.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local-only database.
 *
 * allowBackup is disabled at the manifest level; combined with the Keystore
 * wrapping of key material this means a device backup contains no usable key
 * material even if the database file itself is extracted.
 */
@Database(
    entities = [ContactEntity::class, SessionKeyEntity::class, ClipboardHistoryEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class TurnyptDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun sessionKeyDao(): SessionKeyDao
    abstract fun clipboardHistoryDao(): ClipboardHistoryDao

    companion object {
        const val NAME = "turnypt.db"

        /**
         * v1 → v2 (v0.5.2):
         *  - `session_keys.imported` marks eras that arrived via the peer's
         *    CKS1 invite (existing rows default to locally created — in v1
         *    an imported era was indistinguishable from a created one, and
         *    for gating the passphrase suggestion the safe default is the
         *    side that COULD be choosing the passphrase);
         *  - `clipboard_history` stores the capped, expiring clipboard
         *    history (see ClipboardHistoryEntity's security contract).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE session_keys ADD COLUMN imported INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `clipboard_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`isEnvelope` INTEGER NOT NULL, " +
                        "`truncated` INTEGER NOT NULL, " +
                        "`capturedAt` INTEGER NOT NULL)",
                )
            }
        }
    }
}
