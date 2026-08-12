package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

class QueueAnalysisWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val api=TsetmcClient(); private val dao get()=(applicationContext as BorsaApp).db.dao()
    override suspend fun doWork():Result{
        val candidates=dao.candidateEvents(30) // مرحله‌ای؛ برای فشار نیاوردن به سرویس داده
        if(candidates.isEmpty()) return Result.success()
        for(e in candidates){
            try{
                val arr=api.jsonArrayFrom(api.bestLimitsRaw(e.insCode,e.date),"bestLimitsHistory","bestLimits")
                var bestTime:Int?=null; var bestValue=0.0; var bestImbalance=0.0
                for(i in 0 until arr.length()){
                    val o=arr.optJSONObject(i)?:continue
                    val level=firstInt(o,"number","level")?:1
                    if(level!=1) continue
                    val bp=firstDouble(o,"pMeDem","bidPrice")?:0.0
                    val bv=firstDouble(o,"qTitMeDem","bidVolume")?:0.0
                    val av=firstDouble(o,"qTitMeOf","askVolume")?:0.0
                    val h=firstInt(o,"hEven","time")
                    val qv=bp*bv
                    val imb=if(bv+av>0) bv/(bv+av) else 0.0
                    if(qv>bestValue){bestValue=qv;bestTime=h;bestImbalance=imb}
                }
                // حد اولیه ۵ میلیارد تومان (۵۰ میلیارد ریال) + غلبه تقاضا. بعداً از خود داده کالیبره می‌شود.
                val confirmed=bestValue>=50_000_000_000.0 && bestImbalance>=0.80
                val score=if(confirmed)(70+20*bestImbalance+minOf(10.0,bestValue/500_000_000_000.0*10)).coerceAtMost(99.0) else e.score
                dao.upsertEvents(listOf(e.copy(eventTime=bestTime,queueValue=bestValue,score=score,status=if(confirmed)"QUEUE_CONFIRMED" else "NOT_QUEUE")))
            }catch(_:Exception){ /* برای اجرای بعدی کاندید باقی می‌ماند */ }
            delay(250)
        }
        return Result.success()
    }
}
