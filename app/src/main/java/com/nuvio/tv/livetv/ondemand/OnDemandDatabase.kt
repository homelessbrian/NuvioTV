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

/**
 * What makes two catalog entries the same title: the TMDB id when there is one, otherwise the
 * name and year. Used to show each title once ("Merge duplicates").
 */
private const val DUP_KEY = "CASE WHEN tmdb IS NOT NULL AND tmdb != '' THEN 't' || tmdb ELSE 'n' || norm || '|' || IFNULL(year, '') END"

/** How On Demand lists are sorted (the bar above the posters). */
enum class VodSort(val label: String) {
    DEFAULT("Default"),
    NAME_AZ("A–Z"),
    NAME_ZA("Z–A"),
    NEWEST("Newest"),
    OLDEST("Oldest"),
    RECENTLY_ADDED("Recently added");

    internal fun orderBy(fallback: String): String = when (this) {
        DEFAULT -> fallback
        NAME_AZ -> "norm ASC"
        NAME_ZA -> "norm DESC"
        NEWEST -> "year IS NULL, year DESC, added DESC"
        OLDEST -> "year IS NULL, year ASC"
        RECENTLY_ADDED -> "added DESC"
    }
}

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
    val addedSec: Long,
    /** The provider's genres, as sent ("Action, Comedy"). Not every provider includes them. */
    val genre: String? = null,
    /** With duplicates merged: how many copies of this title there are (qualities, providers). */
    val copies: Int = 1
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
    SQLiteOpenHelper(context, "livetv_on_demand.db", null, 6) {

    init {
        // Browsing On Demand doesn't wait while an import or genre lookup is writing.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items (
                pl TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL,
                name TEXT NOT NULL, norm TEXT NOT NULL, icon TEXT, cat TEXT NOT NULL,
                ext TEXT, tmdb TEXT, year INTEGER, rating TEXT, added INTEGER NOT NULL DEFAULT 0,
                genre TEXT,
                PRIMARY KEY (pl, kind, id))"""
        )
        db.execSQL("CREATE INDEX items_cat ON items(kind, pl, cat)")
        db.execSQL("CREATE INDEX items_norm ON items(kind, norm)")
        db.execSQL("CREATE INDEX items_tmdb ON items(kind, tmdb)")
        db.execSQL("CREATE INDEX items_added ON items(kind, added)")
        createPosterTable(db)
        createPrefixTable(db)
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
        // Version 4: each provider's own title tags ("4K-D+ - ", "4k-NF - ") are learned and
        // removed, so look posters up again with the cleaner titles.
        if (oldVersion < 4) {
            createPrefixTable(db)
            db.execSQL("DELETE FROM posters")
        }
        // Version 5: genres (filled in by the next import).
        if (oldVersion < 5) runCatching { db.execSQL("ALTER TABLE items ADD COLUMN genre TEXT") }
        // Version 6: the matched title's proper name; titles like "007 - A View to a Kill" are
        // looked up again with the improved matching.
        if (oldVersion < 6) {
            runCatching { db.execSQL("ALTER TABLE posters ADD COLUMN title TEXT") }
            db.execSQL("DELETE FROM posters WHERE poster = ''")
        }
    }

    private fun createPrefixTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS prefixes (pl TEXT NOT NULL, prefix TEXT NOT NULL, PRIMARY KEY (pl, prefix))")
    }

    // ------------------------------------------------------------------ provider title tags

    /**
     * Works out a provider's own title tags from its catalog: a short bit before " - ", " | "
     * or ": " that starts many titles ("4K-D+ - Toy Story 5", "4k-NF - Roommates", "EN - …")
     * is a tag, not part of the name. Every provider labels things its own way, so this is
     * learned rather than listed.
     */
    fun learnPrefixes(playlistId: String) {
        val counts = HashMap<String, Int>()
        var total = 0
        readableDatabase.rawQuery("SELECT name FROM items WHERE pl = ?", arrayOf(playlistId)).use { c ->
            while (c.moveToNext()) {
                total++
                leadingSegment(c.getString(0))?.let { counts[it] = (counts[it] ?: 0) + 1 }
            }
        }
        val threshold = maxOf(15, total / 500)
        val learned = counts.filter { (seg, n) -> n >= threshold && seg.split(' ').size <= 4 }.keys
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("prefixes", "pl = ?", arrayOf(playlistId))
            learned.forEach { p ->
                db.insertWithOnConflict("prefixes", null, ContentValues().apply { put("pl", playlistId); put("prefix", p) },
                    SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        loadPrefixes()
    }

    fun hasLearnedPrefixes(playlistId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM prefixes WHERE pl = ? LIMIT 1", arrayOf(playlistId)).use { it.moveToFirst() }

    /** Loads every provider's learned tags into memory (used by [displayTitle]). */
    fun loadPrefixes() {
        val set = HashSet<String>()
        runCatching {
            readableDatabase.rawQuery("SELECT prefix FROM prefixes", null).use { c -> while (c.moveToNext()) set += c.getString(0) }
        }
        knownPrefixes = set
    }

    private fun createPosterTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS posters (uid TEXT PRIMARY KEY, poster TEXT, checked INTEGER NOT NULL, title TEXT)")
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

    fun cachePoster(uid: String, poster: String?, title: String? = null) {
        writableDatabase.insertWithOnConflict("posters", null, ContentValues().apply {
            put("uid", uid); put("poster", poster ?: ""); put("checked", System.currentTimeMillis())
            if (title != null) put("title", title)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The proper name from your addons' match ("A View to a Kill"), if one was found. */
    fun cachedTitle(uid: String): String? =
        readableDatabase.rawQuery("SELECT title FROM posters WHERE uid = ?", arrayOf(uid)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
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
                "INSERT OR REPLACE INTO items (pl, kind, id, name, norm, icon, cat, ext, tmdb, year, rating, added, genre) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
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
                it.genre?.let { v -> stmt.bindString(13, v) } ?: stmt.bindNull(13)
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

    /** Titles still waiting for genres that have a TMDB id, newest first. */
    fun needingGenres(limit: Int): List<VodItem> =
        query("SELECT * FROM items WHERE genre IS NULL AND tmdb IS NOT NULL ORDER BY added DESC LIMIT $limit", arrayListOf())

    fun countNeedingGenres(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items WHERE genre IS NULL AND tmdb IS NOT NULL", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** Marks a title as looked up even when no genres were found (so it isn't retried). */
    fun setGenre(item: VodItem, genres: String) {
        writableDatabase.execSQL(
            "UPDATE items SET genre = ? WHERE pl = ? AND kind = ? AND id = ? AND genre IS NULL",
            arrayOf(genres, item.playlistId, item.kind.key, item.id)
        )
    }

    /** Fills in genres from your addons' match, when the provider didn't send any. */
    fun fillGenre(item: VodItem, genres: String) {
        writableDatabase.execSQL(
            "UPDATE items SET genre = ? WHERE pl = ? AND kind = ? AND id = ? AND (genre IS NULL OR genre = '')",
            arrayOf(genres, item.playlistId, item.kind.key, item.id)
        )
    }

    /** Genres across a kind's catalog, most common first ("Action" 1,204…). */
    fun genres(kind: VodKind): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        val display = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT genre FROM items WHERE kind = ? AND genre IS NOT NULL AND genre != ''", arrayOf(kind.key)).use { c ->
            while (c.moveToNext()) {
                c.getString(0).split(',', '/', '|', '&', ';').map { it.trim() }.filter { it.length in 2..30 }.distinct().forEach { g ->
                    val k = g.lowercase()
                    counts[k] = (counts[k] ?: 0) + 1
                    display.putIfAbsent(k, g.replaceFirstChar { ch -> ch.uppercase() })
                }
            }
        }
        return counts.entries.filter { it.value >= 3 }.sortedByDescending { it.value }.map { display[it.key]!! to it.value }
    }

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
        offset: Int,
        sort: VodSort = VodSort.DEFAULT,
        genre: String? = null,
        merge: Boolean = true
    ): List<VodItem> {
        val where = StringBuilder("kind = ?")
        val args = arrayListOf(kind.key)
        if (genre != null) {
            where.append(" AND genre LIKE ?")
            args += "%$genre%"
        }
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
        val order = sort.orderBy(if (newestFirst) "added DESC" else "rowid")
        val group = if (merge) " GROUP BY $DUP_KEY" else ""
        val copies = if (merge) ", COUNT(*) AS copies" else ""
        return query("SELECT *$copies FROM items WHERE $where$group ORDER BY $order LIMIT $limit OFFSET $offset", args)
    }

    fun search(kind: VodKind, text: String, excludeCategories: Set<String>, limit: Int = 300, sort: VodSort = VodSort.DEFAULT, merge: Boolean = true): List<VodItem> {
        val words = normalizeWords(text)
        if (words.isEmpty()) return emptyList()
        val where = StringBuilder("kind = ?")
        val args = arrayListOf(kind.key)
        words.forEach { w -> where.append(" AND norm LIKE ?"); args += "%$w%" }
        val group = if (merge) " GROUP BY $DUP_KEY" else ""
        val copies = if (merge) ", COUNT(*) AS copies" else ""
        return query("SELECT *$copies FROM items WHERE $where$group ORDER BY ${sort.orderBy("length(name)")} LIMIT $limit", args)
            .filter { "${it.kind.key}:${it.playlistId}:${it.categoryId}" !in excludeCategories }
    }

    /** Every copy of a title: same TMDB id, or same name and year (other quality, other provider). */
    fun versions(item: VodItem): List<VodItem> =
        if (!item.tmdbId.isNullOrBlank()) query("SELECT * FROM items WHERE kind = ? AND tmdb = ?", arrayListOf(item.kind.key, item.tmdbId))
        else query(
            "SELECT * FROM items WHERE kind = ? AND (tmdb IS NULL OR tmdb = '') AND norm = ? AND IFNULL(year, 0) = ?",
            arrayListOf(item.kind.key, normalize(item.name), (item.year ?: 0).toString())
        )

    /** Category names by id, for describing versions. */
    fun categoryNames(playlistId: String, kind: VodKind): Map<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT id, name FROM categories WHERE pl = ? AND kind = ?", arrayOf(playlistId, kind.key)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
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
        addedSec = getLong(getColumnIndexOrThrow("added")),
        genre = getColumnIndex("genre").takeIf { it >= 0 }?.let { i -> if (isNull(i)) null else getString(i) },
        copies = getColumnIndex("copies").takeIf { it >= 0 }?.let { i -> getInt(i) } ?: 1
    )

    private fun Cursor.getStringOrNull(col: String): String? =
        getColumnIndexOrThrow(col).let { i -> if (isNull(i)) null else getString(i) }

    companion object {
        /** Lower case, letters and digits only, with years and quality tags taken out. */
        fun normalize(s: String): String = normalizeWords(s).joinToString("")

        private val PREFIX = Regex("""^(\[[^\]]*\]|\|[^|]*\||[A-Z]{2,4}\s*[-|:]\s+)+""")
        // Built once: building a pattern is slow, and these run for every title on screen.
        private val YEAR_IN_BRACKETS_TRAIL = Regex("""\s*\((19|20)\d{2}\)""")
        private val YEAR_AT_END = Regex("""\s*[-|]\s*(19|20)\d{2}\s*$""")
        private val QUALITY_AT_END = Regex("""(?i)\s*[\[(]?\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|hdr|multi|multi-?sub|vostfr|dub|sub)\b[\])]?\s*$""")
        private val YEAR_WORD = Regex("""(19|20)\d{2}""")
        private val YEAR_ANYWHERE = Regex("""\b(19|20)\d{2}\b""")
        private val YEAR_IN_BRACKETS = Regex("""\((19|20)\d{2}\)""")
        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

        /** Cleaned titles already worked out, by provider title (the same names come up again and again). */
        private val displayTitles = com.nuvio.tv.livetv.data.boundedCache<String, String>(5_000)
        private val NOISE = Regex("""\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|multi|sub|dub|vostfr)\b""")

        /**
         * The title without the provider's decorations: language / country / service tags in
         * front ("EN - ", "|EN| ", "[US] ", "NF - "), a year in brackets, and quality tags.
         * "EN  - Coyote vs. Acme (2026) 4K" becomes "Coyote vs. Acme".
         */
        /** Every provider's learned title tags, lower case. */
        @Volatile var knownPrefixes: Set<String> = emptySet()
            set(value) { field = value; displayTitles.clear() }

        private val SEGMENT = Regex("""^\s*(.{1,30}?)\s*(?:\s[-–—|]\s|\s?\|\s?|:\s)""")

        /** The bit before the first separator, lower case, or null. */
        internal fun leadingSegment(name: String): String? =
            SEGMENT.find(name)?.groupValues?.get(1)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        /**
         * Looks like a provider tag even without learning it: one short "word" (no spaces),
         * mostly capitals, digits or symbols, like "4K-D+", "4k-NF", "EN", "NF", "UHD-DV".
         * Real titles ("Spider-Man", "Mission: Impossible") don't fit.
         */
        private fun looksLikeTag(segment: String): Boolean {
            val t = segment.trim()
            if (t.length !in 2..12 || t.any { it.isWhitespace() }) return false
            if (t.all { it.isDigit() }) return false // "2001 - A Space Odyssey"
            val upper = t.count { it.isUpperCase() }
            val lower = t.count { it.isLowerCase() }
            return upper >= lower && t.any { it.isLetterOrDigit() }
        }

        private fun stripTags(s: String): String {
            var out = s.trim()
            repeat(3) {
                val m = SEGMENT.find(out) ?: return out
                val seg = m.groupValues[1].trim()
                if (seg.lowercase() in knownPrefixes || looksLikeTag(seg)) {
                    val rest = out.substring(m.range.last + 1).trim()
                    if (rest.isBlank()) return out
                    out = rest
                } else return out
            }
            return out
        }

        fun displayTitle(s: String): String {
            displayTitles[s]?.let { return it }
            return cleanTitle(s).also { displayTitles[s] = it }
        }

        private fun cleanTitle(s: String): String {
            val t = stripTags(s).replace(PREFIX, "")
                .replace(YEAR_IN_BRACKETS_TRAIL, "")
                .replace(YEAR_AT_END, "")
                .replace(QUALITY_AT_END, "")
                .trim()
            return t.ifBlank { s.trim() }
        }

        private val TRAILING_EXTRA = setOf("uk", "us", "usa", "au", "aus", "ca", "nz", "ie", "gb", "de", "fr", "es", "it", "nl")

        /**
         * The core of a title for loose matching: cleaned of provider tags, with a trailing year
         * or country tag dropped. "EN - Big Brother UK 2023" and "Big Brother (2023)" both
         * become "bigbrother" (the year is then compared separately).
         */
        fun coreKey(name: String): String {
            val words = normalizeWords(displayTitle(name)).toMutableList()
            while (words.size > 1) {
                val last = words.last()
                if (last in TRAILING_EXTRA || YEAR_WORD.matches(last)) words.removeAt(words.lastIndex) else break
            }
            return words.joinToString("")
        }

        /** A year in a provider title ("Big Brother UK 2023"), if any. */
        fun yearInTitle(name: String): Int? = YEAR_ANYWHERE.findAll(name).lastOrNull()?.value?.toIntOrNull()

        fun normalizeWords(s: String): List<String> =
            s.replace(PREFIX, "")
                .replace(YEAR_IN_BRACKETS, " ")
                .lowercase()
                .replace(NOISE, " ")
                .split(NON_WORD)
                .filter { it.isNotBlank() }
    }
}
