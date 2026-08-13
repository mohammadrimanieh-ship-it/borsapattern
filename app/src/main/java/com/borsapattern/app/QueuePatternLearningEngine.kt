package com.borsapattern.app

import android.content.Context

object QueuePatternLearningEngine {
    suspend fun rebuild(context:Context){
        val app=context.applicationContext as BorsaApp
        val db=app.db.openHelper.readableDatabase
        val prefs=context.getSharedPreferences("queue_learning",Context.MODE_PRIVATE)

        val c=db.query("""
          SELECT signalTime,queueValue,score,nextDayQueueStatus
          FROM queue_events
          WHERE status='QUEUE_CONFIRMED'
            AND nextDayQueueStatus IN ('QUEUE_AGAIN','NOT_QUEUE_NEXT_DAY')
            AND signalTime BETWEEN 90000 AND 123000
          ORDER BY date ASC
        """.trimIndent())

        var total=0
        var success=0
        var earlyTotal=0
        var earlySuccess=0
        var midTotal=0
        var midSuccess=0
        var lateTotal=0
        var lateSuccess=0
        val successTimes=mutableListOf<Int>()
        val successValues=mutableListOf<Double>()
        val successScores=mutableListOf<Double>()

        c.use{
            while(it.moveToNext()){
                val t=if(it.isNull(0)) 0 else it.getInt(0)
                val q=if(it.isNull(1)) 0.0 else it.getDouble(1)
                val score=if(it.isNull(2)) 0.0 else it.getDouble(2)
                val status=it.getString(3)
                val ok=status=="QUEUE_AGAIN"

                total++
                if(ok){
                    success++
                    if(t>0) successTimes+=t
                    if(q>0) successValues+=q
                    successScores+=score
                }

                when{
                    t<100000 -> {
                        earlyTotal++
                        if(ok) earlySuccess++
                    }
                    t<113000 -> {
                        midTotal++
                        if(ok) midSuccess++
                    }
                    else -> {
                        lateTotal++
                        if(ok) lateSuccess++
                    }
                }
            }
        }

        fun rate(ok:Int,n:Int):Float =
            if(n>0) ok.toFloat()/n.toFloat() else 0f

        fun medianInt(xs:List<Int>):Int{
            if(xs.isEmpty()) return 0
            val s=xs.sorted()
            return s[s.size/2]
        }
        fun medianDouble(xs:List<Double>):Double{
            if(xs.isEmpty()) return 0.0
            val s=xs.sorted()
            return s[s.size/2]
        }

        val successRate=rate(success,total)
        val earlyRate=rate(earlySuccess,earlyTotal)
        val midRate=rate(midSuccess,midTotal)
        val lateRate=rate(lateSuccess,lateTotal)

        val bestBucket=listOf(
            Triple("09:00–10:00",earlyRate,earlyTotal),
            Triple("10:00–11:30",midRate,midTotal),
            Triple("11:30–12:30",lateRate,lateTotal)
        ).filter{it.third>=5}
         .maxByOrNull{it.second}

        prefs.edit()
            .putInt("total_known",total)
            .putInt("success_count",success)
            .putFloat("success_rate",successRate)
            .putInt("early_total",earlyTotal)
            .putFloat("early_rate",earlyRate)
            .putInt("mid_total",midTotal)
            .putFloat("mid_rate",midRate)
            .putInt("late_total",lateTotal)
            .putFloat("late_rate",lateRate)
            .putInt("median_success_time",medianInt(successTimes))
            .putLong("median_success_queue_value",medianDouble(successValues).toLong())
            .putFloat(
                "avg_success_score",
                if(successScores.isEmpty()) 0f
                else successScores.average().toFloat()
            )
            .putString("best_bucket",bestBucket?.first ?: "داده کافی نیست")
            .putFloat("best_bucket_rate",bestBucket?.second ?: 0f)
            .putLong("updated_at",System.currentTimeMillis())
            .apply()
    }

    fun liveTimeBoost(context:Context,time:Int):Double{
        val p=context.getSharedPreferences("queue_learning",Context.MODE_PRIVATE)
        val n=p.getInt("total_known",0)
        if(n<20) return 0.0
        val baseline=p.getFloat("success_rate",0f).toDouble()
        val bucket=when{
            time<100000 -> p.getFloat("early_rate",0f).toDouble()
            time<113000 -> p.getFloat("mid_rate",0f).toDouble()
            else -> p.getFloat("late_rate",0f).toDouble()
        }
        // Maximum learned adjustment is ±8 points.
        return ((bucket-baseline)*20.0).coerceIn(-8.0,8.0)
    }
}
