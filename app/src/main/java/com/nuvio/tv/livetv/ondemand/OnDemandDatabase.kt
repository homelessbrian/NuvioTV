package com.nuvio.tv.livetv.ondemand

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class VodKind(val key: String) { MOVIE("movie"), SERIES("series") }

/** One movie or series from a provider's On Demand catalog. */
data class VodItem(
    val playlistId: String,
    val kind: VodKind,
    /** stream_id for movies, series_id for series. */
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    /** Movies: the file type the provider serves (mp4, mkv…). */
    val ext: String?,
    val tmdbId: String?,
    val year: Int?,
    val rating: String?,
    val addedSec: Long
) {
    val uid: String get() = "${kind.key}:$playlistId:$id"
}

data class VodCategory(
    val playlistId: String,
    val kind: VodKind,
    val id: String,
    val name: String,
    val count: Int
) {
    /** Also the key used for hiding and locking ("movie:<playlist>:<category>"). */
    val uid: String get() = "${kind.key}:$playlistId:$id"
}

/**
 * On-device index of every provider's movies and series. Providers can list 100,000+ titles,
 * which would be far too much to keep in memory, so they live in a small SQLite database and
 * are read a page at a time.
 */
@Singleton
class OnDemandDatabase @Inject constructor(@ApplicationContext context: Context) :
    SQLiteOpenHelper(context, "livetv_on_demand.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items (
                pl TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL,
                name TEXT NOT NULL, norm TEXT NOT NULL, icon TEXT, cat TEXT NOT NULL,
                ext TEXT, tmdb TEXT, year INTEGER, rating TEXT, added INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (pl, kind, id))"""
        )
        db.execSQL("CREATE INDEX items_cat ON items(kind, pl, cat)")
        db.execSQL("CREATE INDEX items_norm ON items(kind, norm)")
        db.execSQL("CREATE INDEX items_tmdb ON items(kind, tmdb)")
        db.execSQL("CREATE INDEX items_added ON items(kind, added)")
        createPosterTable(db)
        db.execSQL(
            """CREATE TABLE categories (
                pl TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
                position INTEGER NOT NULL, PRIMARY KEY (pl, kind, id))"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2 only adds the poster cache; the imported catalogs are kept.
        if (oldVersion < 2) createPosterTable(db)
        // Version 3: titles are cleaned and matched by IMDb id now, so forget the old
        // "no poster found" answers (the catalogs themselves are kept).
        if (oldVersion < 3) db.execSQL("DELETE FROM posters")
    }

    private fun createPosterTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS posters (uid TEXT PRIMARY KEY, poster TEXT, checked INTEGER NOT NULL)")
    }

    // ------------------------------------------------------------------ poster cache

    /**
     * Posters found in your addons, remembered between app starts so On Demand doesn't look
     * them up again every time. An empty value means "none of your addons had it" (retried
     * after a week).
     */
    fun cachedPoster(uid: String): Pair<String, Long>? =
        readableDatabase.rawQuery("SELECT poster, checked FROM posters WHERE uid = ?", arrayOf(uid)).use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: "") to c.getLong(1) else null
        }

    fun cachePoster(uid: String, poster: String?) {
        writableDatabase.insertWithOnConflict("posters", null, ContentValues().apply {
            put("uid", uid); put("poster", poster ?: ""); put("checked", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Category names, for parental locks on "Watch On Demand" results. */
    fun categoryName(playlistId: String, kind: VodKind, id: String): String? =
        readableDatabase.rawQuery(
            "SELECT name FROM categories WHERE pl = ? AND kind = ? AND id = ?", arrayOf(playlistId, kind.key, id)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    // ------------------------------------------------------------------ writing

    /** Replaces one provider's catalog of [kind] in a single transaction. */
    fun replace(playlistId: String, kind: VodKind, categories: List<Pair<String, String>>, items: Sequence<VodItem>): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            db.delete("items", "pl = ? AND kind = ?", arrayOf(playlistId, kind.key))
            db.delete("categories", "pl = ? AND kind = ?", arrayOf(playlistId, kind.key))
            categories.forEachIndexed { i, (id, name) ->
                db.insertWithOnConflict("categories", null, ContentValues().apply {
                    put("pl", playlistId); put("kind", kind.key); put("id", id); put("name", name); put("position", i)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO items (pl, kind, id, name, norm, icon, cat, ext, tmdb, year, rating, added) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            items.forEach { it ->
                stmt.clearBindings()
                stmt.bindString(1, playlistId)
                stmt.bindString(2, kind.key)
                stmt.bindString(3, it.id)
                stmt.bindString(4, it.name)
                stmt.bindString(5, normalize(it.name))
                it.icon?.let { v -> stmt.bindString(6, v) } ?: stmt.bindNull(6)
                stmt.bindString(7, it.categoryId)
                it.ext?.let { v -> stmt.bindString(8, v) } ?: stmt.bindNull(8)
                it.tmdbId?.let { v -> stmt.bindString(9, v) } ?: stmt.bindNull(9)
                it.year?.let { v -> stmt.bindLong(10, v.toLong()) } ?: stmt.bindNull(10)
                it.rating?.let { v -> stmt.bindString(11, v) } ?: stmt.bindNull(11)
                stmt.bindLong(12, it.addedSec)
                stmt.executeInsert()
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    /** Removes providers that no longer import On Demand. */
    fun removeExcept(keepPlaylistIds: Set<String>) {
        val db = writableDatabase
        val existing = HashSet<String>()
        db.rawQuery("SELECT DISTINCT pl FROM items UNION SELECT DISTINCT pl FROM categories", null).use { c ->
            while (c.moveToNext()) existing += c.getString(0)
        }
        (existing - keepPlaylistIds).forEach { pl ->
            db.delete("items", "pl = ?", arrayOf(pl))
            db.delete("categories", "pl = ?", arrayOf(pl))
        }
    }

    // ------------------------------------------------------------------ reading

    fun hasAny(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM items LIMIT 1", null).use { it.moveToFirst() }

    fun count(kind: VodKind): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items WHERE kind = ?", arrayOf(kind.key)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun categories(kind: VodKind): List<VodCategory> {
        val out = ArrayList<VodCategory>()
        readableDatabase.rawQuery(
            """SELECT c.pl, c.id, c.name, (SELECT COUNT(*) FROM items i WHERE i.pl = c.pl AND i.kind = c.kind AND i.cat = c.id)
               FROM categories c WHERE c.kind = ? ORDER BY c.pl, c.position""",
            arrayOf(kind.key)
        ).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(3)
                if (n > 0) out += VodCategory(c.getString(0), kind, c.getString(1), c.getString(2), n)
            }
        }
        return out
    }

    /** A page of items: in one category, or across all (optionally newest first). */
    fun items(
        kind: VodKind,
        category: VodCategory?,
        excludeCategories: Set<String>,
        newestFirst: Boolean,
        limit: Int,
        offset: Int
    ): List<VodItem> {
        val where = StringBuilder("kind = ?")
        val args = arrayListOf(kind.key)
        if (category != null) {
            where.append(" AND pl = ? AND cat = ?")
            args += category.playlistId; args += category.id
        }
        excludeCategories.forEach { uid ->
            val parts = uid.split(':', limit = 3)
            if (parts.size == 3 && parts[0] == kind.key) {
                where.append(" AND NOT (pl = ? AND cat = ?)")
                args += parts[1]; args += parts[2]
            }
        }
        val order = if (newestFirst) "added DESC" else "rowid"
        return query("SELECT * FROM items WHERE $where ORDER BY $order LIMIT $limit OFFSET $offset", args)
    }

    fun search(kind: VodKind, text: String, excludeCategories: Set<String>, limit: Int = 300): List<VodItem> {
        val words = normalizeWords(text)
        if (words.isEmpty()) return emptyList()
        val where = StringBuilder("kind = ?")
        val args = arrayListOf(kind.key)
        words.forEach { w -> where.append(" AND norm LIKE ?"); args += "%$w%" }
        return query("SELECT * FROM items WHERE $where ORDER BY length(name) LIMIT $limit", args)
            .filter { "${it.kind.key}:${it.playlistId}:${it.categoryId}" !in excludeCategories }
    }

    fun byTmdb(kind: VodKind, tmdbId: String): List<VodItem> =
        query("SELECT * FROM items WHERE kind = ? AND tmdb = ?", arrayListOf(kind.key, tmdbId))

    fun byTitle(kind: VodKind, title: String): List<VodItem> =
        query("SELECT * FROM items WHERE kind = ? AND norm = ?", arrayListOf(kind.key, normalize(title)))

    fun item(playlistId: String, kind: VodKind, id: String): VodItem? =
        query("SELECT * FROM items WHERE pl = ? AND kind = ? AND id = ?", arrayListOf(playlistId, kind.key, id)).firstOrNull()

    private fun query(sql: String, args: List<String>): List<VodItem> {
        val out = ArrayList<VodItem>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c -> while (c.moveToNext()) out += c.toItem() }
        return out
    }

    private fun Cursor.toItem(): VodItem = VodItem(
        playlistId = getString(getColumnIndexOrThrow("pl")),
        kind = if (getString(getColumnIndexOrThrow("kind")) == VodKind.SERIES.key) VodKind.SERIES else VodKind.MOVIE,
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        icon = getStringOrNull("icon"),
        categoryId = getString(getColumnIndexOrThrow("cat")),
        ext = getStringOrNull("ext"),
        tmdbId = getStringOrNull("tmdb"),
        year = getColumnIndexOrThrow("year").let { i -> if (isNull(i)) null else getInt(i) },
        rating = getStringOrNull("rating"),
        addedSec = getLong(getColumnIndexOrThrow("added"))
    )

    private fun Cursor.getStringOrNull(col: String): String? =
        getColumnIndexOrThrow(col).let { i -> if (isNull(i)) null else getString(i) }

    companion object {
        /** Lower case, letters and digits only, with years and quality tags taken out. */
        fun normalize(s: String): String = normalizeWords(s).joinToString("")

        private val PREFIX = Regex("""^(\[[^\]]*\]|\|[^|]*\||[A-Z]{2,4}\s*[-|:]\s+)+""")
        private val NOISE = Regex("""\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|multi|sub|dub|vostfr)\b""")

        /**
         * The title without the provider's decorations: language / country / service tags in
         * front ("EN - ", "|EN| ", "[US] ", "NF - "), a year in brackets, and quality tags.
         * "EN  - Coyote vs. Acme (2026) 4K" becomes "Coyote vs. Acme".
         */
        fun displayTitle(s: String): String {
            val t = s.replace(PREFIX, "")
                .replace(Regex("""\s*\((19|20)\d{2}\)"""), "")
                .replace(Regex("""\s*[-|]\s*(19|20)\d{2}\s*$"""), "")
                .replace(Regex("""(?i)\s*[\[(]?\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|hdr|multi|multi-?sub|vostfr|dub|sub)\b[\])]?\s*$"""), "")
                .trim()
            return t.ifBlank { s.trim() }
        }

        fun normalizeWords(s: String): List<String> =
            s.replace(PREFIX, "")
                .replace(Regex("""\((19|20)\d{2}\)"""), " ")
                .lowercase()
                .replace(NOISE, " ")
                .split(Regex("""[^\p{L}\p{N}]+"""))
                .filter { it.isNotBlank() }
    }
}
