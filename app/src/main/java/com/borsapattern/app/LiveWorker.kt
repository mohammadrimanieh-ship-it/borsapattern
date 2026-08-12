package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LiveWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result{
        return try{
            val app=applicationContext as BorsaApp
            val api=TsetmcClient()
            val arr=api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
            val out=mutableListOf<LiveScoreEntity>()

            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                val ins=firstString(o,"insCode","instrumentId")?:continue
                val sym=cleanSymbol(
                    firstString(o,"lVal18AFC","symbol","instrumentName"),
                    ins
                )
                val last=firstDouble(o,"pl","pDrCotVal","lastPrice")?:continue
                val y=firstDouble(o,"py","priceYesterday","yesterdayPrice")?:continue
                val vol=firstDouble(o,"qTotTran5J","volume")?:0.0

                val priceMomentum=((last/y)-1.0).coerceAtLeast(0.0)/0.05
                val volumeAccel=(vol/5_000_000.0).coerceIn(0.0,1.0)
                val score=PatternEngine.scoreLive(
                    priceMomentum,
                    volumeAccel,
                    0.35,
                    0.25
                )

                val reason=buildString{
                    if(priceMomentum>0.5) append("شتاب قیمت؛ ")
                    if(volumeAccel>0.5) append("افزایش حجم؛ ")
                    if(isEmpty()) append("در حال پایش")
                }
                out += LiveScoreEntity(
                    insCode=ins,
                    symbol=sym,
                    score=score,
                    reason=reason,
                    updatedAt=System.currentTimeMillis()
                )
            }

            app.db.dao().upsertScores(out)

            val top=app.db.dao().topScores().firstOrNull()
            if(top!=null && top.score>=80 && !top.symbol.isNullOrBlank()){
                Notifications.show(
                    applicationContext,
                    top.symbol,
                    top.score,
                    top.reason
                )
            }
            Result.success()
        }catch(_:Exception){
            Result.retry()
        }
    }
}
