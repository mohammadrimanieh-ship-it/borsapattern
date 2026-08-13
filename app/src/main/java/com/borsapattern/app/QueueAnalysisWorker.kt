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
        PatternEngine.seedInitialEvents((applicationContext as BorsaApp).db)
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
        }

        Result.success()
    }

    private suspend fun analyzeOne(e:QueueEventEntity){
        try{
            val dayHigh=dao.dailyFor(e.insCode,e.date)?.high ?: 0.0

            // OkHttp itself has a call timeout too; this coroutine timeout is an extra guard.
            val arr=withTimeout(16_000L){
                api.jsonArrayFrom(
                    api.bestLimitsRaw(e.insCode,e.date),
                    "bestLimitsHistory",
                    "bestLimits"
                )
            }

            if(arr.length()==0){
                dao.upsertEvents(listOf(e.copy(status="NOT_QUEUE")))
                return
            }

            var bidPrice:Double?=null
            var bidVolume:Double?=null
            var askVolume:Double?=null
            var bestTime:Int?=null
            var firstSignalTime:Int?=null
            var bestValue=0.0
            var bestImbalance=0.0
            var atHighSeen=false

            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                if((firstInt(o,"number","level")?:1)!=1) continue

                firstDouble(o,"pMeDem","bidPrice")?.let{bidPrice=it}
                firstDouble(o,"qTitMeDem","bidVolume")?.let{bidVolume=it}
                firstDouble(o,"qTitMeOf","askVolume")?.let{askVolume=it}

                val bp=bidPrice?:continue
                val bv=bidVolume?:0.0
                val av=askVolume?:0.0
                val qv=bp*bv
                val imbalance=if(bv+av>0) bv/(bv+av) else 0.0
                val atHigh=dayHigh>0 && bp>=dayHigh*0.9995
                val nowTime=firstInt(o,"hEven","time")
                if(firstSignalTime==null && atHigh && qv>=20_000_000_000.0 && imbalance>=0.65){
                    firstSignalTime=nowTime
                }

                if(atHigh && qv>bestValue){
                    bestValue=qv
                    bestTime=nowTime
                    bestImbalance=imbalance
                    atHighSeen=true
                }
            }

            val confirmed=
                atHighSeen &&
                bestValue>=50_000_000_000.0 &&
                bestImbalance>=0.80

            val score=if(confirmed){
                (
                    70 +
                    20*bestImbalance +
                    minOf(9.0,bestValue/500_000_000_000.0*10)
                ).coerceAtMost(99.0)
            }else e.score

            dao.upsertEvents(
                listOf(
                    e.copy(
                        eventTime=bestTime,
                        queueValue=bestValue,
                        score=score,
                        signalTime=firstSignalTime,
                        status=if(confirmed)"QUEUE_CONFIRMED" else "NOT_QUEUE"
                    )
                )
            )
        }catch(_:Exception){
            // Do not leave a candidate stuck forever.
            dao.upsertEvents(listOf(e.copy(status="ERROR")))
        }
    }

    companion object{
        const val ANALYSIS_CHAIN="queue_analysis_chain"
    }
}
