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
        if(modelVersion<3){
            prefs.edit()
                .putBoolean("analysis_running",true)
                .putString("analysis_status","بازسازی مدل صف معتبر و حذف بازگشایی‌های ویژه")
                .apply()
            PatternEngine.rebuildCandidates((applicationContext as BorsaApp).db)
            prefs.edit().putInt("analysis_model_version",3).apply()
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
                            score=0.0,
                            eventTime=null,
                            signalTime=null,
                            queueValue=null,
                            nextDayQueueStatus="SKIPPED_SPECIAL_REOPEN"
                        )
                    )
                )
                return
            }

            val dayHigh=dao.dailyFor(e.insCode,e.date)?.high ?: 0.0
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

            var firstQueueTime:Int?=null
            var lastQueueTime:Int?=null
            var bestValue=0.0
            var bestImbalance=0.0
            var queueSamples=0

            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                val rowTime=firstInt(o,"hEven","time")
                if(rowTime==null || rowTime !in 90000..123000) continue
                if((firstInt(o,"number","level")?:1)!=1) continue

                val bp=firstDouble(o,"pMeDem","bidPrice") ?: continue
                val bv=firstDouble(o,"qTitMeDem","bidVolume") ?: 0.0
                val av=firstDouble(o,"qTitMeOf","askVolume") ?: 0.0
                if(bv<=0) continue

                val qv=bp*bv
                val imbalance=if(bv+av>0) bv/(bv+av) else 0.0
                val atHigh=dayHigh>0 && bp>=dayHigh*0.9995
                val realQueue=atHigh && (av<=0.0 || imbalance>=0.92)

                if(realQueue){
                    queueSamples++
                    if(firstQueueTime==null || rowTime<firstQueueTime!!) firstQueueTime=rowTime
                    if(lastQueueTime==null || rowTime>lastQueueTime!!) lastQueueTime=rowTime
                    if(qv>bestValue) bestValue=qv
                    if(imbalance>bestImbalance) bestImbalance=imbalance
                }
            }

            val confirmed=queueSamples>0 && firstQueueTime!=null
            val durationMinutes=if(confirmed && lastQueueTime!=null){
                val f=firstQueueTime!!
                val l=lastQueueTime!!
                val fm=(f/10000)*60 + (f/100)%100
                val lm=(l/10000)*60 + (l/100)%100
                (lm-fm).coerceAtLeast(0)
            }else 0

            val valueComponent=
                (bestValue/250_000_000_000.0*18.0).coerceIn(0.0,18.0)
            val durationComponent=(durationMinutes/90.0*12.0).coerceIn(0.0,12.0)
            val imbalanceComponent=(bestImbalance*20.0).coerceIn(0.0,20.0)

            val score=if(confirmed){
                (50.0+valueComponent+durationComponent+imbalanceComponent)
                    .coerceIn(50.0,99.0)
            }else e.score

            dao.upsertEvents(
                listOf(
                    e.copy(
                        eventTime=firstQueueTime,
                        queueValue=bestValue.takeIf{confirmed},
                        score=score,
                        signalTime=firstQueueTime,
                        status=if(confirmed)"QUEUE_CONFIRMED" else "NOT_QUEUE"
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
