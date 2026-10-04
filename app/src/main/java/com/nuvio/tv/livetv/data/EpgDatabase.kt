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
    SQLiteOpenHelper(context, "livetv_guide.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE programs (
                ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL,
                descr TEXT, cat TEXT, ep TEXT, icon TEXT, year INTEGER, people TEXT)"""
        )
        db.execSQL("CREATE INDEX programs_ch_start ON programs(ch, start)")
        db.execSQL("CREATE INDEX programs_stop ON programs(stop)")
        db.execSQL("CREATE TABLE auto (ch TEXT PRIMARY KEY, source TEXT NOT NULL, xmltv TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS programs")
        db.execSQL("DROP TABLE IF EXISTS auto")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    /** Replaces the whole guide (after a guide update), in one transaction. */
    fun replaceAll(fingerprint: String, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
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
    fun range(fromMs: Long, toMs: Long, onlyChannel: String? = null): Map<String, List<EpgProgram>> {
        val out = HashMap<String, ArrayList<EpgProgram>>()
        val sql = "SELECT ch, start, stop, title, descr, cat, ep, icon, year, people FROM programs " +
            "WHERE " + (if (onlyChannel != null) "ch = ? AND " else "") + "stop > ? AND start < ? ORDER BY ch, start"
        val args = listOfNotNull(onlyChannel, fromMs.toString(), toMs.toString()).toTypedArray()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val p = EpgProgram(
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
        return out
    }

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

    fun clear() {
        val db = writableDatabase
        db.delete("programs", null, null)
        db.delete("auto", null, null)
        db.delete("meta", null, null)
    }
}
