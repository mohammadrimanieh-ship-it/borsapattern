package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LiveWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result{
        return try{
            val app=applicationContext as BorsaApp
            val dao=app.db.dao()
            val map=dao.allSymbols().associateBy{it.insCode}
            val wanted=MarketPrefs.selected(applicationContext)
            val api=TsetmcClient()
            val arr=api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
            val out=mutableListOf<LiveScoreEntity>()

            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                val ins=firstString(o,"insCode","instrumentId")?:continue
                val meta=map[ins]
                if(meta!=null && !wanted.contains(meta.segment)) continue
                val sym=meta?.symbol ?: cleanSymbol(firstString(o,"lVal18AFC","symbol"),ins)
                val last=firstDouble(o,"pl","pDrCotVal","lastPrice")?:continue
                val y=firstDouble(o,"py","priceYesterday","yesterdayPrice")?:continue
                val vol=firstDouble(o,"qTotTran5J","volume")?:0.0
                val priceMomentum=((last/y)-1.0).coerceAtLeast(0.0)/0.05
                val volumeAccel=(vol/5_000_000.0).coerceIn(0.0,1.0)
                val score=PatternEngine.scoreLive(priceMomentum,volumeAccel,0.35,0.25)
                val reason=buildString{
                    if(priceMomentum>0.5) append("شتاب قیمت؛ ")
                    if(volumeAccel>0.5) append("افزایش حجم؛ ")
                    if(isEmpty()) append("در حال پایش")
                }
                out += LiveScoreEntity(ins,sym,score,reason,System.currentTimeMillis())
            }
            dao.upsertScores(out)
            Result.success()
        }catch(_:Exception){ Result.retry() }
    }
}
