package com.borsapattern.app

import androidx.room.withTransaction

object PatternEngine {
    suspend fun seedInitialEvents(db: AppDatabase) {
        val sql = db.openHelper.writableDatabase
        val c = sql.query("""
            SELECT insCode,date,high,yesterday,volume,value
            FROM daily
            WHERE yesterday > 0 AND volume > 0
        """.trimIndent())
        val events = mutableListOf<QueueEventEntity>()
        c.use {
            while (it.moveToNext()) {
                val ins = it.getString(0)
                val date = it.getInt(1)
                val high = if (it.isNull(2)) null else it.getDouble(2)
                val y = if (it.isNull(3)) null else it.getDouble(3)
                val vol = if (it.isNull(4)) null else it.getDouble(4)
                if (high != null && y != null && y > 0) {
                    val rise = high / y - 1.0
                    if (rise >= 0.025) {
                        val seedScore = (50 + (rise*500)).coerceIn(50.0, 75.0)
                        events += QueueEventEntity(ins,date,null,null,seedScore,"CANDIDATE")
                    }
                }
            }
        }
        db.dao().upsertEvents(events)
    }

    fun scoreLive(
        priceMomentum: Double,
        volumeAccel: Double,
        bidAskImbalance: Double,
        supplyDrop: Double
    ): Double {
        val z =
            0.30 * priceMomentum.coerceIn(0.0,1.0) +
            0.25 * volumeAccel.coerceIn(0.0,1.0) +
            0.30 * bidAskImbalance.coerceIn(0.0,1.0) +
            0.15 * supplyDrop.coerceIn(0.0,1.0)
        return (z * 100.0).coerceIn(0.0,100.0)
    }
}
