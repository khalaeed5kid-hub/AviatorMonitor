package com.aviatortracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Rect

/** قاعدة بيانات مستقلة لأداة صافي الشركة (لا تلمس results.db الخاصة بالأداة القديمة). */
class HouseDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "house.db", null, 1) {

    data class Row(val bets: Long, val wins: Long, val net: Long, val ts: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE rounds(id INTEGER PRIMARY KEY AUTOINCREMENT, session INTEGER NOT NULL, " +
                "bets INTEGER NOT NULL, wins INTEGER NOT NULL, net INTEGER NOT NULL, ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun add(session: Int, r: HouseRound) {
        val cv = ContentValues().apply {
            put("session", session)
            put("bets", r.betsCents)
            put("wins", r.winsCents)
            put("net", r.netCents)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("rounds", null, cv)
    }

    @Synchronized
    fun balance(session: Int): Long {
        readableDatabase.rawQuery("SELECT COALESCE(SUM(net),0) FROM rounds WHERE session=?", arrayOf(session.toString())).use {
            return if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    @Synchronized
    fun rounds(session: Int): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(
            "SELECT bets,wins,net,ts FROM rounds WHERE session=? ORDER BY id ASC", arrayOf(session.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Row(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)))
        }
        return out
    }
}

/** إعدادات الأداة الجديدة (SharedPreferences منفصلة عن الأداة القديمة). */
object HousePrefs {
    private const val P = "house"
    private fun sp(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun region(c: Context, i: Int): Rect? {
        val p = sp(c).getString("region$i", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: return null
        return if (p.size == 4) Rect(p[0], p[1], p[2], p[3]) else null
    }

    fun setRegion(c: Context, i: Int, r: Rect) {
        sp(c).edit().putString("region$i", "${r.left},${r.top},${r.right},${r.bottom}").apply()
    }

    /** رقم الجلسة الحالية: تصفير الرصيد = جلسة جديدة (السجل القديم يبقى في القاعدة). */
    fun session(c: Context): Int = sp(c).getInt("session", 1)
    fun newSession(c: Context) { sp(c).edit().putInt("session", session(c) + 1).apply() }

    fun cumulative(c: Context): Boolean = sp(c).getBoolean("cumulative", false)
    fun setCumulative(c: Context, v: Boolean) { sp(c).edit().putBoolean("cumulative", v).apply() }

    fun paused(c: Context): Boolean = sp(c).getBoolean("paused", false)
    fun setPaused(c: Context, v: Boolean) { sp(c).edit().putBoolean("paused", v).apply() }
}

/** تشخيص الأداة الجديدة (في الذاكرة فقط). */
object HouseDiag {
    @Volatile var running = false
    @Volatile var rawBets = ""
    @Volatile var rawWins = ""
    @Volatile var bets: Long? = null
    @Volatile var wins: Long? = null
    @Volatile var active = false
    @Volatile var maxBets = 0L
    @Volatile var maxWins = 0L
    @Volatile var lastRound: HouseRound? = null
    @Volatile var savedCount = 0
    @Volatile var frames = 0L
    @Volatile var error = ""
    @Volatile var crop1: Bitmap? = null
    @Volatile var crop2: Bitmap? = null
}
