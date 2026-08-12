package com.borsapattern.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class QueueAnalysisWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val api=TsetmcClient()
    private val dao get()=(applicationContext as BorsaApp).db.dao()

    override suspend fun doWork():Result{
        // مهم: استخراج الگو باید خودش کاندیدها را بسازد؛
        // نسخه قبلی وقتی جدول کاندید خالی بود فوراً تمام می‌شد.
        PatternEngine.seedInitialEvents((applicationContext as BorsaApp).db)

        if(inputData.getBoolean("resetErrors",false)){
            dao.retryErrors()
        }

        val candidates=dao.candidateEvents(80)
        if(candidates.isEmpty()) return Result.success(
            workDataOf("stage" to "کاندیدی برای بررسی باقی نمانده")
        )

        var done=0
        for(e in candidates){
            try{
                val daily=dao.dailyFor(e.insCode,e.date)
                val dayHigh=daily?.high ?: 0.0

                val arr=api.jsonArrayFrom(
                    api.bestLimitsRaw(e.insCode,e.date),
                    "bestLimitsHistory","bestLimits"
                )

                // BestLimits تاریخی می‌تواند به‌صورت تغییرات پیاپی باشد.
                // وضعیت سطح اول سفارش را در طول زمان بازسازی می‌کنیم.
                var bidPrice:Double?=null
                var bidVolume:Double?=null
                var bidCount:Int?=null
                var askPrice:Double?=null
                var askVolume:Double?=null
                var askCount:Int?=null

                var bestTime:Int?=null
                var bestValue=0.0
                var bestImbalance=0.0
                var bestAtDayHigh=false

                for(i in 0 until arr.length()){
                    val o=arr.optJSONObject(i)?:continue
                    val level=firstInt(o,"number","level")?:1
                    if(level!=1) continue

                    firstDoubleOrNull(o,"pMeDem","bidPrice")?.let{bidPrice=it}
                    firstDoubleOrNull(o,"qTitMeDem","bidVolume")?.let{bidVolume=it}
                    firstIntOrNull(o,"zOrdMeDem","bidCount")?.let{bidCount=it}
                    firstDoubleOrNull(o,"pMeOf","askPrice")?.let{askPrice=it}
                    firstDoubleOrNull(o,"qTitMeOf","askVolume")?.let{askVolume=it}
                    firstIntOrNull(o,"zOrdMeOf","askCount")?.let{askCount=it}

                    val bp=bidPrice ?: continue
                    val bv=bidVolume ?: 0.0
                    val av=askVolume ?: 0.0
                    val h=firstInt(o,"hEven","time")

                    val qv=bp*bv
                    val imb=if(bv+av>0) bv/(bv+av) else 0.0
                    val atHigh=dayHigh>0 && bp>=dayHigh*0.9995

                    if(atHigh && qv>bestValue){
                        bestValue=qv
                        bestTime=h
                        bestImbalance=imb
                        bestAtDayHigh=true
                    }
                }

                // برچسب اولیه برای ساخت دیتاست:
                // تقاضای حداقل ۵ میلیارد تومان در حوالی سقف روز + غلبه سمت خرید.
                val confirmed=
                    bestAtDayHigh &&
                    bestValue>=50_000_000_000.0 &&
                    bestImbalance>=0.80

                val score=if(confirmed){
                    (70+20*bestImbalance+
                        minOf(9.0,bestValue/500_000_000_000.0*10))
                        .coerceAtMost(99.0)
                }else e.score

                dao.upsertEvents(listOf(
                    e.copy(
                        eventTime=bestTime,
                        queueValue=bestValue,
                        score=score,
                        status=if(confirmed)"QUEUE_CONFIRMED" else "NOT_QUEUE"
                    )
                ))
            }catch(_:Exception){
                // خطای یک نماد نباید زنجیره تحلیل را برای همیشه قفل کند.
                dao.upsertEvents(listOf(e.copy(status="ERROR")))
            }

            done++
            setProgress(workDataOf("stage" to "تحلیل صف تاریخی","processed" to done))
            delay(180)
        }

        // اگر هنوز کاندید باقی است، بدون نیاز به باز بودن برنامه ادامه می‌دهد.
        if(dao.candidateCount()>0){
            val net=Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val next=OneTimeWorkRequestBuilder<QueueAnalysisWorker>()
                .setConstraints(net)
                .setInitialDelay(5,TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(applicationContext).enqueue(next)
        }

        return Result.success()
    }

    private fun firstDoubleOrNull(o:org.json.JSONObject,vararg keys:String):Double?{
        for(k in keys){
            if(o.has(k)&&!o.isNull(k)){
                val v=o.optDouble(k,Double.NaN)
                if(!v.isNaN()) return v
            }
        }
        return null
    }

    private fun firstIntOrNull(o:org.json.JSONObject,vararg keys:String):Int?{
        for(k in keys){
            if(o.has(k)&&!o.isNull(k)) return o.optInt(k)
        }
        return null
    }
}
