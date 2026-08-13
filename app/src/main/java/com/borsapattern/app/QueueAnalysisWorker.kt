package com.borsapattern.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QueueAnalysisWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val api=TsetmcClient()
    private val dao get()=(applicationContext as BorsaApp).db.dao()
    private val prefs get()=applicationContext.getSharedPreferences("analysis",Context.MODE_PRIVATE)

    override suspend fun doWork():Result=coroutineScope{
        val modelVersion=prefs.getInt("analysis_model_version",0)
        if(modelVersion<5){
            prefs.edit()
                .putBoolean("analysis_running",true)
                .putString("analysis_status","بازسازی مدل صف پایدار و حذف صف‌های لحظه‌ای")
                .apply()
            val db=(applicationContext as BorsaApp).db
            db.openHelper.writableDatabase.execSQL("DELETE FROM prequeue_snapshots")
            PatternEngine.rebuildCandidates(db)
            applicationContext.getSharedPreferences("prequeue_backtest",Context.MODE_PRIVATE)
                .edit().clear().apply()
            prefs.edit().putInt("analysis_model_version",5).apply()
        }else{
            PatternEngine.seedInitialEvents((applicationContext as BorsaApp).db)
        }
        if(inputData.getBoolean("resetErrors",false)) dao.retryErrors()

        val segments=MarketPrefs.selectedSegments(applicationContext).toList()
        val types=MarketPrefs.selectedTypes(applicationContext).toList()
        val batchSize=inputData.getInt("batchSize",120).coerceIn(20,240)
        val parallelism=inputData.getInt("parallelism",4).coerceIn(1,6)
        val candidates=dao.candidateEventsFor(segments,types,batchSize)

        if(candidates.isEmpty()){
            prefs.edit()
                .putBoolean("analysis_running",false)
                .putInt("analysis_batch_done",0)
                .putInt("analysis_batch_total",0)
                .putString("analysis_status","تحلیل دسته‌های انتخاب‌شده کامل شد")
                .apply()
            return@coroutineScope Result.success()
        }

        prefs.edit()
            .putBoolean("analysis_running",true)
            .putInt("analysis_batch_total",candidates.size)
            .putInt("analysis_batch_done",0)
            .putString("analysis_status","شروع تحلیل سریع صف‌ها")
            .apply()

        val done=AtomicInteger(0)

        // Process only a few requests at once. This avoids blocking dozens of IO threads
        // on Semaphore.acquire(), which was the main reason progress could stay at 0/240.
        for(chunk in candidates.chunked(parallelism)){
            chunk.map{e->
                async(Dispatchers.IO){
                    analyzeOne(e)
                    val n=done.incrementAndGet()
                    prefs.edit()
                        .putInt("analysis_batch_done",n)
                        .putString("analysis_status","تحلیل سریع: $n از ${candidates.size}")
                        .apply()
                    setProgress(workDataOf("processed" to n,"total" to candidates.size))
                }
            }.awaitAll()
            yield()
        }

        val remaining=dao.candidateCountFor(segments,types)
        prefs.edit()
            .putBoolean("analysis_running",remaining>0)
            .putString(
                "analysis_status",
                if(remaining>0) "این مرحله تمام شد؛ $remaining کاندید باقی مانده"
                else "تحلیل دسته‌های انتخاب‌شده کامل شد"
            )
            .apply()

        if(remaining>0){
            val next=OneTimeWorkRequestBuilder<QueueAnalysisWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInputData(
                    workDataOf(
                        "batchSize" to batchSize,
                        "parallelism" to parallelism,
                        "resetErrors" to false
                    )
                )
                .setInitialDelay(2,TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                ANALYSIS_CHAIN,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                next
            )
        }else{
            prefs.edit()
                .putString("analysis_status","تحلیل صف‌ها کامل شد؛ بررسی خودکار روز معاملاتی بعد شروع شد")
                .apply()

            val nextDay=OneTimeWorkRequestBuilder<NextDayQueueWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                NextDayQueueWorker.CHAIN,
                ExistingWorkPolicy.REPLACE,
                nextDay
            )
        }

        Result.success()
    }

    private suspend fun analyzeOne(e:QueueEventEntity){
        try{
            if(PatternEngine.isLikelySpecialReopen(dao,e.insCode,e.date)){
                dao.upsertEvents(
                    listOf(
                        e.copy(
                            status="SPECIAL_REOPEN",
                            score=0.0,eventTime=null,signalTime=null,queueValue=null,
                            nextDayQueueStatus="SKIPPED_SPECIAL_REOPEN",
                            queueDurationMinutes=0,queuePersistenceRatio=0.0,
                            queueBreakCount=0,queueEndHeld=false,
                            queueValueRetention=0.0
                        )
                    )
                )
                return
            }

            val dayHigh=dao.dailyFor(e.insCode,e.date)?.high ?: 0.0
            val arr=withTimeout(16_000L){
                api.jsonArrayFrom(
                    api.bestLimitsRaw(e.insCode,e.date),
                    "bestLimitsHistory","bestLimits"
                )
            }

            if(arr.length()==0){
                dao.upsertEvents(listOf(e.copy(status="NOT_QUEUE")))
                return
            }

            data class BookState(
                val time:Int,
                val isQueue:Boolean,
                val queueValue:Double,
                val imbalance:Double
            )

            var hadPreopenQueue=false
            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                val rowTime=firstInt(o,"hEven","time") ?: continue
                if(rowTime !in 84500..85959) continue
                if((firstInt(o,"number","level")?:1)!=1) continue

                val bp=firstDouble(o,"pMeDem","bidPrice") ?: continue
                val bv=firstDouble(o,"qTitMeDem","bidVolume") ?: 0.0
                val av=firstDouble(o,"qTitMeOf","askVolume") ?: 0.0
                val imbalance=if(bv+av>0) bv/(bv+av) else 0.0
                val atHigh=dayHigh>0 && bp>=dayHigh*0.9995
                if(bv>0 && atHigh && (av<=0.0 || imbalance>=0.92)){
                    hadPreopenQueue=true
                    break
                }
            }

            if(hadPreopenQueue){
                dao.upsertEvents(
                    listOf(
                        e.copy(
                            status="PREOPEN_QUEUE",
                            score=0.0,eventTime=null,signalTime=null,queueValue=null,
                            nextDayQueueStatus="SKIPPED_PREOPEN_DAY1",
                            queueDurationMinutes=0,queuePersistenceRatio=0.0,
                            queueBreakCount=0,queueEndHeld=false,
                            queueValueRetention=0.0
                        )
                    )
                )
                return
            }

            val states=mutableListOf<BookState>()
            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                val rowTime=firstInt(o,"hEven","time") ?: continue
                if(rowTime !in 90000..123000) continue
                if((firstInt(o,"number","level")?:1)!=1) continue

                val bp=firstDouble(o,"pMeDem","bidPrice") ?: continue
                val bv=firstDouble(o,"qTitMeDem","bidVolume") ?: 0.0
                val av=firstDouble(o,"qTitMeOf","askVolume") ?: 0.0
                val imbalance=if(bv+av>0) bv/(bv+av) else 0.0
                val atHigh=dayHigh>0 && bp>=dayHigh*0.9995
                val queue=bv>0 && atHigh && (av<=0.0 || imbalance>=0.92)
                states += BookState(
                    rowTime,queue,
                    if(queue) bp*bv else 0.0,
                    imbalance
                )
            }
            states.sortBy{it.time}

            val firstIdx=states.indexOfFirst{it.isQueue}
            if(firstIdx<0){
                dao.upsertEvents(listOf(e.copy(status="NOT_QUEUE")))
                return
            }

            val post=states.drop(firstIdx)
            val queued=post.filter{it.isQueue}
            val firstQueue=post.first().time
            val lastQueue=queued.lastOrNull()?.time ?: firstQueue
            val bestValue=queued.maxOfOrNull{it.queueValue} ?: 0.0
            val bestImbalance=queued.maxOfOrNull{it.imbalance} ?: 0.0
            val persistence=
                if(post.isNotEmpty()) queued.size.toDouble()/post.size.toDouble() else 0.0

            var breaks=0
            var wasQueued=true
            for(s in post.drop(1)){
                if(wasQueued && !s.isQueue) breaks++
                wasQueued=s.isQueue
            }

            val tail=post.takeLast(3)
            val endHeld=tail.isNotEmpty() &&
                tail.count{it.isQueue} >= ((tail.size+1)/2)

            fun minuteOf(t:Int)=(t/10000)*60+(t/100)%100
            val duration=(minuteOf(lastQueue)-minuteOf(firstQueue)).coerceAtLeast(0)
            val remaining=(12*60+30-minuteOf(firstQueue)).coerceAtLeast(0)
            val requiredDuration=minOf(15,maxOf(5,remaining/3))

            val lastHeldValue=
                if(endHeld) tail.filter{it.isQueue}.lastOrNull()?.queueValue ?: 0.0
                else 0.0
            val valueRetention=
                if(bestValue>0) (lastHeldValue/bestValue).coerceIn(0.0,1.0) else 0.0

            val persistent =
                queued.size>=2 &&
                duration>=requiredDuration &&
                persistence>=0.65 &&
                breaks<=3 &&
                (endHeld || persistence>=0.80)

            val valueComponent=
                (bestValue/250_000_000_000.0*14.0).coerceIn(0.0,14.0)
            val durationComponent=(duration/90.0*12.0).coerceIn(0.0,12.0)
            val imbalanceComponent=(bestImbalance*12.0).coerceIn(0.0,12.0)
            val persistenceComponent=(persistence*22.0).coerceIn(0.0,22.0)
            val retentionComponent=(valueRetention*10.0).coerceIn(0.0,10.0)
            val breakPenalty=(breaks*3.0).coerceAtMost(12.0)

            val score=(
                38.0+valueComponent+durationComponent+imbalanceComponent+
                persistenceComponent+retentionComponent-breakPenalty
            ).coerceIn(0.0,99.0)

            dao.upsertEvents(
                listOf(
                    e.copy(
                        eventTime=firstQueue,
                        queueValue=bestValue.takeIf{bestValue>0},
                        score=score,
                        signalTime=if(persistent) firstQueue else null,
                        status=if(persistent)"QUEUE_CONFIRMED" else "FRAGILE_QUEUE",
                        nextDayQueueStatus=if(persistent)"PENDING" else "SKIPPED_FRAGILE",
                        queueDurationMinutes=duration,
                        queuePersistenceRatio=persistence,
                        queueBreakCount=breaks,
                        queueEndHeld=endHeld,
                        queueValueRetention=valueRetention
                    )
                )
            )
        }catch(_:Exception){
            dao.upsertEvents(listOf(e.copy(status="ERROR")))
        }
    }

    companion object{
        const val ANALYSIS_CHAIN="queue_analysis_chain"
    }
}
