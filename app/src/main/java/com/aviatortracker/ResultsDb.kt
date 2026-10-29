package com.aviatortracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ResultsDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "results.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE results(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "multiplier REAL NOT NULL, ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun add(multiplier: Double) {
        val cv = ContentValues().apply {
            put("multiplier", multiplier)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("results", null, cv)
    }

    @Synchronized
    fun all(): List<Pair<Double, Long>> {
        val out = ArrayList<Pair<Double, Long>>()
        readableDatabase.rawQuery("SELECT multiplier, ts FROM results ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) out.add(c.getDouble(0) to c.getLong(1))
        }
        return out
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("results", null, null)
    }
}
