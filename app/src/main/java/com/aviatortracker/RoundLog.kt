package com.aviatortracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * السجل الموحّد: كل صف = جولة واحدة.
 * رصيد الشركة ومجموعا الرهانات والمدفوعات محفوظة في جدول meta مستقل عن الصفوف، وتُحدَّث في نفس عملية إضافة الجولة
 * (transaction)، فحذف السجل لا يمسّ الرصيد، وتصفير الرصيد وحده (جلسة جديدة) يصفّره.
 */
class RoundLogDb private constructor(private val appCtx: Context) :
    SQLiteOpenHelper(appCtx, "roundlog.db", null, 2) {

    companion object {
        @Volatile private var inst: RoundLogDb? = null
        /** نسخة واحدة مشتركة بين الخدمة والواجهة (لا اتصالان متنافسان على الملف). */
        fun get(ctx: Context): RoundLogDb =
            inst ?: synchronized(this) { inst ?: RoundLogDb(ctx.applicationContext).also { inst = it } }
    }

    data class Row(
        val id: Long, val session: Int, val ts: Long, val mult: Double?,
        val bets: Long?, val wins: Long?, val net: Long?,
        val before: Long, val after: Long, val status: Int
    )

    /** balance/totalIn/totalOut منذ آخر تصفير للرصيد؛ count/flagged لجلسة الحالية. */
    data class Summary(val balance: Long, val totalIn: Long, val totalOut: Long, val count: Int, val flagged: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, session INTEGER NOT NULL, ts INTEGER NOT NULL, " +
                "mult REAL, bets INTEGER, wins INTEGER, net INTEGER, bal_before INTEGER NOT NULL, " +
                "bal_after INTEGER NOT NULL, status INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v INTEGER NOT NULL)")
            // ترحيل: نحسب الأرصدة من صفوف الجلسة الحالية (كانت مشتقة منها في النسخة السابقة)
            val session = HousePrefs.session(appCtx).toString()
            db.rawQuery(
                "SELECT COALESCE(SUM(net),0), COALESCE(SUM(bets),0), COALESCE(SUM(wins),0) FROM log " +
                    "WHERE session=? AND status IN (0,1)", arrayOf(session)
            ).use { c ->
                if (c.moveToFirst()) {
                    setMeta(db, "balance", c.getLong(0)); setMeta(db, "in", c.getLong(1)); setMeta(db, "out", c.getLong(2))
                }
            }
        }
    }

    private fun getMeta(db: SQLiteDatabase, k: String): Long =
        db.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun setMeta(db: SQLiteDatabase, k: String, v: Long) {
        db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)", arrayOf<Any>(k, v))
    }

    /** يضيف جولة ويحدّث الرصيد والمجاميع في عملية واحدة: لا يمكن إضافة الصافي مرتين ولا نصف إضافة. */
    @Synchronized
    fun add(session: Int, rec: RoundRecord): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val before = getMeta(db, "balance")
            val step = Ledger.next(before, rec)
            val cv = ContentValues().apply {
                put("session", session)
                put("ts", rec.ts)
                if (rec.mult != null) put("mult", rec.mult) else putNull("mult")
                if (rec.betsCents != null) put("bets", rec.betsCents) else putNull("bets")
                if (rec.winsCents != null) put("wins", rec.winsCents) else putNull("wins")
                val n = rec.netCents
                if (n != null) put("net", n) else putNull("net")
                put("bal_before", step.before)
                put("bal_after", step.after)
                put("status", rec.status)
            }
            val id = db.insert("log", null, cv)
            if (RoundStatus.counted(rec.status) && rec.betsCents != null && rec.winsCents != null) {
                setMeta(db, "balance", step.after)
                setMeta(db, "in", Ledger.sat(getMeta(db, "in"), rec.betsCents))
                setMeta(db, "out", Ledger.sat(getMeta(db, "out"), rec.winsCents))
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun summary(session: Int): Summary {
        val db = readableDatabase
        val bal = getMeta(db, "balance")
        val tin = getMeta(db, "in")
        val tout = getMeta(db, "out")
        db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(CASE WHEN status IN (2,3) THEN 1 ELSE 0 END),0) FROM log WHERE session=?",
            arrayOf(session.toString())
        ).use { c ->
            return if (c.moveToFirst()) Summary(bal, tin, tout, c.getInt(0), c.getInt(1)) else Summary(bal, tin, tout, 0, 0)
        }
    }

    private fun read(sql: String, args: Array<String>): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Row(
                        c.getLong(0), c.getInt(1), c.getLong(2),
                        if (c.isNull(3)) null else c.getDouble(3),
                        if (c.isNull(4)) null else c.getLong(4),
                        if (c.isNull(5)) null else c.getLong(5),
                        if (c.isNull(6)) null else c.getLong(6),
                        c.getLong(7), c.getLong(8), c.getInt(9)
                    )
                )
            }
        }
        return out
    }

    private val cols = "id,session,ts,mult,bets,wins,net,bal_before,bal_after,status"

    /** الأحدث أولًا، للجلسة الحالية. */
    @Synchronized
    fun recent(session: Int, limit: Int): List<Row> =
        read("SELECT $cols FROM log WHERE session=? ORDER BY id DESC LIMIT $limit", arrayOf(session.toString()))

    /** كل السجل (كل الجلسات) بالترتيب الزمني، للنسخ. */
    @Synchronized
    fun all(): List<Row> = read("SELECT $cols FROM log ORDER BY id ASC", emptyArray())

    /** يحذف صفوف السجل فقط. الرصيد والمجاميع لا تتغير. */
    @Synchronized
    fun clearRows() {
        writableDatabase.delete("log", null, null)
    }

    /** تصفير الرصيد والمجاميع (يُستدعى مع بدء جلسة جديدة). الصفوف تبقى. */
    @Synchronized
    fun resetTotals() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            setMeta(db, "balance", 0); setMeta(db, "in", 0); setMeta(db, "out", 0)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

/** إعدادات التحكم (SharedPreferences). جلسة جديدة = تصفير الرصيد مع بقاء السجل. */
object HousePrefs {
    private fun sp(c: Context) = c.getSharedPreferences("house", Context.MODE_PRIVATE)

    fun session(c: Context): Int = sp(c).getInt("session", 1)
    fun newSession(c: Context) { sp(c).edit().putInt("session", session(c) + 1).apply(); bump(c) }

    /** عدّاد تغيّر السجل من خارج الخدمة (تصفير/حذف) لتحديث اللوحة العائمة. */
    fun version(c: Context): Int = sp(c).getInt("version", 0)
    fun bump(c: Context) { sp(c).edit().putInt("version", version(c) + 1).apply() }

    fun cumulative(c: Context): Boolean = sp(c).getBoolean("cumulative", false)
    fun setCumulative(c: Context, v: Boolean) { sp(c).edit().putBoolean("cumulative", v).apply() }

    /** إيقاف مؤقت لمراقبة الأموال فقط. */
    fun moneyPaused(c: Context): Boolean = sp(c).getBoolean("paused", false)
    fun setMoneyPaused(c: Context, v: Boolean) { sp(c).edit().putBoolean("paused", v).apply() }

    /** إيقاف مؤقت لمراقبة الإحصائيات فقط — مستقل عن الأموال. */
    fun statsPaused(c: Context): Boolean = sp(c).getBoolean("statsPaused", false)
    fun setStatsPaused(c: Context, v: Boolean) { sp(c).edit().putBoolean("statsPaused", v).apply() }
}

/**
 * حفظ المناطق الثلاث: 1 = المضاعف/النتائج، 2 = مجموع الرهانات، 3 = مجموع الأرباح.
 * تُحفظ لكل أبعاد شاشة على حدة؛ وإن لم توجد للأبعاد الحالية تُستعاد المناطق المحفوظة بالنسخ السابقة إذا كانت ضمن حدود الشاشة.
 */
object RegionStore {
    private fun sp(c: Context) = c.getSharedPreferences("regions", Context.MODE_PRIVATE)

    private fun legacy(c: Context, id: Int): String? = when (id) {
        1 -> c.getSharedPreferences("tracker", Context.MODE_PRIVATE).getString("region", null)
        2 -> c.getSharedPreferences("house", Context.MODE_PRIVATE).getString("region1", null)
        else -> c.getSharedPreferences("house", Context.MODE_PRIVATE).getString("region2", null)
    }

    fun get(c: Context, id: Int, w: Int, h: Int): Rect? {
        val a = RegionCodec.decode(sp(c).getString(RegionCodec.key(id, w, h), null))
            ?: RegionCodec.decode(legacy(c, id))?.takeIf { RegionCodec.fits(it, w, h) }
            ?: return null
        return Rect(a[0], a[1], a[2], a[3])
    }

    fun set(c: Context, id: Int, w: Int, h: Int, r: Rect) {
        sp(c).edit().putString(RegionCodec.key(id, w, h), RegionCodec.encode(r.left, r.top, r.right, r.bottom)).apply()
    }

    fun anySaved(c: Context): Boolean = sp(c).all.isNotEmpty() || (1..3).any { legacy(c, it) != null }
}

/** تشخيص الأموال والسجل (في الذاكرة فقط). */
object HouseDiag {
    @Volatile var running = false
    @Volatile var rawBets = ""
    @Volatile var rawWins = ""
    @Volatile var bets: Long? = null
    @Volatile var wins: Long? = null
    @Volatile var active = false
    @Volatile var maxBets = 0L
    @Volatile var maxWins = 0L
    @Volatile var lastRecord: RoundRecord? = null
    @Volatile var recordedCount = 0
    @Volatile var frames = 0L
    @Volatile var error = ""
    @Volatile var crop1: Bitmap? = null
    @Volatile var crop2: Bitmap? = null
    @Volatile var balance = 0L
    @Volatile var totalIn = 0L
    @Volatile var totalOut = 0L
    @Volatile var regionStats = false
    @Volatile var regionBets = false
    @Volatile var regionWins = false
}
