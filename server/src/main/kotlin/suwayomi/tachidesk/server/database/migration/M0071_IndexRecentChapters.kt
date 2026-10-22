package suwayomi.tachidesk.server.database.migration

import de.neonew.exposed.migrations.helpers.SQLMigration

/** Additive index only: recent pages can stop scanning once the requested window is filled. */
@Suppress("ClassName", "unused")
class M0071_IndexRecentChapters : SQLMigration() {
    override val sql: String =
        "CREATE INDEX IF NOT EXISTS idx_chapter_recent ON CHAPTER (fetched_at DESC, id DESC);"
}
