package com.borsapattern.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class NextDayQueueWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val dao get()=(applicationContext as BorsaApp).db.dao()
    private val api=TsetmcClient()
    private val prefs get()=applicationContext.getSharedPreferences("nextday",Context.MODE_PRIVATE)

    override suspend fun doWork():Result=coroutineScope{
        val items=dao.pendingNextDayChecks(40)
        if(items.isEmpty()){
            prefs.edit().putString("status","بررسی روز معاملاتی بعد کامل شد").apply()
            QueuePatternLearningEngine.rebuild(applicationContext)
            return@coroutineScope Result.success()
        }
        prefs.edit().putString("status","در حال بررسی ماندگاری صف روز بعد").apply()
        for(chunk in items.chunked(3)){
            chunk.map{e->async(Dispatchers.IO){checkOne(e)}}.awaitAll()
        }
        if(dao.pendingNextDayChecks(1).isNotEmpty()){
            val n=OneTimeWorkRequestBuilder<NextDayQueueWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInitialDelay(3,TimeUnit.SECONDS).build()
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(CHAIN,ExistingWorkPolicy.APPEND_OR_REPLACE,n)
        } else {
            prefs.edit().putString("status","بررسی روز معاملاتی بعد کامل شد").apply()
            QueuePatternLearningEngine.rebuild(applicationContext)
        }
        Result.success()
    }

    private suspend fun checkOne(e:QueueEventEntity){
        val next=dao.nextTradingDaily(e.insCode,e.date) ?: run {
            dao.updateNextDayResult(e.insCode,e.date,null,"NO_NEXT_DAY")
            return
        }

        if(PatternEngine.isLikelySpecialReopen(dao,e.insCode,next.date)){
            dao.updateNextDayResult(
                e.insCode,e.date,next.date,"NEXT_DAY_SPECIAL_REOPEN"
            )
            return
        }

        try{
            val arr=withTimeout(15_000L){
                api.jsonArrayFrom(
                    api.bestLimitsRaw(e.insCode,next.date),
                    "bestLimitsHistory","bestLimits"
                )
            }
            val high=next.high ?: 0.0
            var ok=false
            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                val rowTime=firstInt(o,"hEven","time")
                if(rowTime==null || rowTime !in 90000..123000) continue
                if((firstInt(o,"number","level")?:1)!=1) continue

                val p=firstDouble(o,"pMeDem","bidPrice") ?: continue
                val b=firstDouble(o,"qTitMeDem","bidVolume") ?: 0.0
                val a=firstDouble(o,"qTitMeOf","askVolume") ?: 0.0
                if(b<=0) continue
                val imb=if(b+a>0)b/(b+a) else 0.0
                val realQueue=high>0 && p>=high*0.9995 && (a<=0.0 || imb>=0.92)
                if(realQueue){
                    ok=true
                    break
                }
            }

            dao.updateNextDayResult(
                e.insCode,e.date,next.date,
                if(ok)"QUEUE_AGAIN" else "NOT_QUEUE_NEXT_DAY"
            )
        }catch(_:Exception){}
    }

    companion object{const val CHAIN="next_day_queue_chain"}
}
