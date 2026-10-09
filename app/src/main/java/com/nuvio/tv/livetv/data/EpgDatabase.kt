package com.nuvio.tv.livetv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgProgram
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TV guide on the device, TiviMate style: every listing is stored here after a guide
 * update, and the app reads only the hours it needs (what's on screen, plus a margin), so Live
 * TV opens straight away however big the guide is, and uses far less memory.
 */
@Singleton
class EpgDatabase @Inject constructor(@ApplicationContext context: Context) :
    SQLiteOpenHelper(context, "livetv_guide.db", null, 2) {

    init {
        // Readers don't wait for writers: the guide stays readable (scrolling, catch-up) while a
        // guide update is being stored, instead of stalling until it finishes.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE programs (
                ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL,
                descr TEXT, cat TEXT, ep TEXT, icon TEXT, year INTEGER, people TEXT)"""
        )
        db.execSQL("CREATE INDEX programs_ch_start ON programs(ch, start)")
        db.execSQL("CREATE INDEX programs_stop ON programs(stop)")
        db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)")
        db.execSQL("CREATE TABLE auto (ch TEXT PRIMARY KEY, source TEXT NOT NULL, xmltv TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2: an index on start time, so "the hours around now" is read directly instead
        // of going through every future listing (which took minutes on big guides).
        if (oldVersion < 2) {
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)") }
                .onFailure {
                    db.execSQL("DROP TABLE IF EXISTS programs")
                    db.execSQL("DROP TABLE IF EXISTS auto")
                    db.execSQL("DROP TABLE IF EXISTS meta")
                    onCreate(db)
                }
        }
    }

    /** Replaces the whole guide (after a guide update), in one transaction. */
    fun replaceAll(fingerprint: String, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DROP INDEX IF EXISTS programs_ch_start")
            db.execSQL("DROP INDEX IF EXISTS programs_stop")
            db.execSQL("DROP INDEX IF EXISTS programs_start")
            db.delete("programs", null, null)
            db.delete("auto", null, null)
            val stmt = db.compileStatement(
                "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
            )
            programs.forEach { (ch, list) ->
                list.forEach { p ->
                    stmt.clearBindings()
                    stmt.bindString(1, ch)
                    stmt.bindLong(2, p.startMs)
                    stmt.bindLong(3, p.stopMs)
                    stmt.bindString(4, p.title)
                    p.description?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                    p.category?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                    p.episode?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
                    p.icon?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
                    p.year?.let { stmt.bindLong(9, it.toLong()) } ?: stmt.bindNull(9)
                    if (p.people.isEmpty()) stmt.bindNull(10) else stmt.bindString(10, p.people.joinToString("\u001F"))
                    stmt.executeInsert()
                }
            }
            auto.forEach { (ch, a) ->
                db.insertWithOnConflict("auto", null, ContentValues().apply {
                    put("ch", ch); put("source", a.sourceId); put("xmltv", a.xmltvId)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS programs_ch_start ON programs(ch, start)")
            db.execSQL("CREATE INDEX IF NOT EXISTS programs_stop ON programs(stop)")
            db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)")
            db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "fingerprint"); put("v", fingerprint) },
                SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun fingerprint(): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'fingerprint'", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Listings overlapping [fromMs, toMs), by channel, in time order. */
    /**
     * Listings in [fromMs, toMs), by channel, in time order.
     *  - [light]: just the parts the guide grid needs (title, times, episode, image), without
     *    descriptions and cast. Several times quicker to read and much smaller in memory;
     *    details are fetched for the show you're looking at ([program]).
     *  - [channels]: only these channels (the group on screen first, the rest afterwards).
     */
    fun range(
        fromMs: Long,
        toMs: Long,
        onlyChannel: String? = null,
        light: Boolean = false,
        channels: Collection<String>? = null
    ): Map<String, List<EpgProgram>> {
        val out = HashMap<String, ArrayList<EpgProgram>>()
        val columns = if (light) "ch, start, stop, title, ep, icon" else "ch, start, stop, title, descr, cat, ep, icon, year, people"
        // Read by start time (indexed): shows starting in the window, plus ones that started up
        // to [MAX_SHOW_MS] earlier and are still on. Looking up "everything that ends after the
        // start of the window" instead meant reading every future listing in the guide.
        val timeArgs = listOf((fromMs - MAX_SHOW_MS).toString(), toMs.toString(), fromMs.toString())
        fun read(chFilter: String, chArgs: List<String>) {
            val sql = "SELECT $columns FROM programs WHERE $chFilter start >= ? AND start < ? AND stop > ?"
            readableDatabase.rawQuery(sql, (chArgs + timeArgs).toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val p = if (light) EpgProgram(
                        startMs = c.getLong(1),
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        episode = if (c.isNull(4)) null else c.getString(4),
                        icon = if (c.isNull(5)) null else c.getString(5)
                    ) else EpgProgram(
                        startMs = c.getLong(1),
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        description = if (c.isNull(4)) null else c.getString(4),
                        category = if (c.isNull(5)) null else c.getString(5),
                        episode = if (c.isNull(6)) null else c.getString(6),
                        icon = if (c.isNull(7)) null else c.getString(7),
                        year = if (c.isNull(8)) null else c.getInt(8),
                        people = if (c.isNull(9)) emptyList() else c.getString(9).split('\u001F')
                    )
                    out.getOrPut(c.getString(0)) { ArrayList() } += p
                }
            }
        }
        when {
            onlyChannel != null -> read("ch = ? AND", listOf(onlyChannel))
            channels != null -> channels.distinct().chunked(400).forEach { part ->
                read("ch IN (" + part.joinToString(",") { "?" } + ") AND", part)
            }
            else -> read("", emptyList())
        }
        out.values.forEach { l -> l.sortBy { it.startMs } }
        return out
    }

    /** One show's full details (description, cast…), for the show you're looking at. */
    fun program(ch: String, startMs: Long): EpgProgram? =
        range(startMs, startMs + 1, onlyChannel = ch)[ch]?.firstOrNull { it.startMs == startMs }

    /** One channel's listings across [fromMs, toMs) (overlay mode's full schedule, catch-up). */
    fun channelRange(ch: String, fromMs: Long, toMs: Long): List<EpgProgram> =
        range(fromMs, toMs, ch)[ch].orEmpty()

    fun autoMatches(): Map<String, EpgAssignment> {
        val out = HashMap<String, EpgAssignment>()
        readableDatabase.rawQuery("SELECT ch, source, xmltv FROM auto", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = EpgAssignment(c.getString(1), c.getString(2))
        }
        return out
    }

    /**
     * Adds a channel's listings for a time span (Xtream catch-up archive), replacing anything
     * stored for that channel in that span.
     */
    fun putChannelRange(ch: String, fromMs: Long, toMs: Long, programs: List<EpgProgram>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("programs", "ch = ? AND start >= ? AND start < ?", arrayOf(ch, fromMs.toString(), toMs.toString()))
            val stmt = db.compileStatement(
                "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
            )
            programs.forEach { p ->
                stmt.clearBindings()
                stmt.bindString(1, ch); stmt.bindLong(2, p.startMs); stmt.bindLong(3, p.stopMs); stmt.bindString(4, p.title)
                p.description?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                stmt.bindNull(6); stmt.bindNull(7); stmt.bindNull(8); stmt.bindNull(9); stmt.bindNull(10)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        val db = writableDatabase
        db.delete("programs", null, null)
        db.delete("auto", null, null)
        db.delete("meta", null, null)
    }
}

/** Longest single listing assumed when reading a time window (longer ones are very rare). */
private const val MAX_SHOW_MS = 6L * 60 * 60 * 1000
