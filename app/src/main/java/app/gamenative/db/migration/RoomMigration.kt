package app.gamenative.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

private const val DROP_TABLE = "DROP TABLE IF EXISTS " // Trailing Space

internal val ROOM_MIGRATION_V7_to_V8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        // Dec 5, 2025: Friends and Chat features removed
        connection.execSQL(DROP_TABLE + "chat_message")
        connection.execSQL(DROP_TABLE + "emoticon")
        connection.execSQL(DROP_TABLE + "steam_friend")
    }
}

internal val ROOM_MIGRATION_V10_to_V11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        // Add exclude column to gog_games table if not present
        // This explicit migration ensures the column exists even if AutoMigration fails
        connection.execSQL("ALTER TABLE gog_games ADD COLUMN exclude INTEGER NOT NULL DEFAULT 0")
    }
}
