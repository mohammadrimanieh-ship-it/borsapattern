package com.borsapattern.app

object PatternEngine {
    suspend fun seedInitialEvents(db:AppDatabase) {
        val sql=db.openHelper.writableDatabase
        val c=sql.query("""
          SELECT d.insCode,d.date,d.high,d.yesterday,d.volume,d.value
          FROM daily d LEFT JOIN queue_events e ON e.insCode=d.insCode AND e.date=d.date
          WHERE d.yesterday>0 AND d.volume>0 AND e.insCode IS NULL
        """.trimIndent())
        val events=mutableListOf<QueueEventEntity>()
        c.use {
            while(it.moveToNext()){
                val ins=it.getString(0); val date=it.getInt(1)
                val high=if(it.isNull(2)) null else it.getDouble(2)
                val y=if(it.isNull(3)) null else it.getDouble(3)
                val vol=if(it.isNull(4)) 0.0 else it.getDouble(4)
                val value=if(it.isNull(5)) 0.0 else it.getDouble(5)
                if(high!=null && y!=null && y>0){
                    val rise=high/y-1.0
                    // مرحله اول فقط روزهایی را نگه می‌دارد که به محدوده مثبت قوی رسیده‌اند و معامله معنادار داشته‌اند.
                    if(rise>=0.035 && vol>0 && value>0){
                        val score=(55.0 + rise*400.0).coerceIn(55.0,78.0)
                        events += QueueEventEntity(ins,date,null,null,score,"CANDIDATE")
                    }
                }
            }
        }
        if(events.isNotEmpty()) db.dao().upsertEvents(events)
    }

    fun scoreLive(priceMomentum:Double,volumeAccel:Double,bidAskImbalance:Double,supplyDrop:Double):Double {
        val z=.30*priceMomentum.coerceIn(0.0,1.0)+.25*volumeAccel.coerceIn(0.0,1.0)+.30*bidAskImbalance.coerceIn(0.0,1.0)+.15*supplyDrop.coerceIn(0.0,1.0)
        return (z*100.0).coerceIn(0.0,100.0)
    }
}
