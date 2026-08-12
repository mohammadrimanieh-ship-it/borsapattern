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

                val rawSym=cleanSymbol(firstString(o,"lVal18AFC","symbol","instrumentName"),ins)
                val sym=meta?.symbol ?: rawSym ?: meta?.name

                // اگر MarketWatch نام را اینجا داد، همان لحظه جدول نماد هم ترمیم شود.
                if(meta!=null && meta.symbol.isNullOrBlank() && rawSym!=null){
                    dao.upsertSymbols(listOf(meta.copy(symbol=rawSym)))
                }

                val last=firstDouble(o,"pl","pDrCotVal","lastPrice")?:continue
                val y=firstDouble(o,"py","priceYesterday","yesterdayPrice")?:continue
                val vol=firstDouble(o,"qTotTran5J","volume")?:0.0

                val priceMomentum=if(y>0) (((last/y)-1.0).coerceAtLeast(0.0)/0.05).coerceIn(0.0,1.0) else 0.0
                val volumeAccel=(vol/5_000_000.0).coerceIn(0.0,1.0)

                val patternScore=PatternEngine.scoreLive(priceMomentum,volumeAccel,0.35,0.25)
                val volumeScore=(volumeAccel*100.0).coerceIn(0.0,100.0)
                val tech=TechnicalEngine.calculate(dao.recentDaily(ins,220),last)

                val finalScore=(
                    patternScore*0.45 +
                    tech.score*0.30 +
                    volumeScore*0.25
                ).coerceIn(0.0,100.0)

                val reason=buildString{
                    append("الگو ${patternScore.toInt()} • تکنیکال ${tech.score.toInt()} • حجم ${volumeScore.toInt()}")
                    append(" | ")
                    append(tech.summary)
                }

                out += LiveScoreEntity(
                    insCode=ins,
                    symbol=sym,
                    score=finalScore,
                    reason=reason,
                    updatedAt=System.currentTimeMillis(),
                    patternScore=patternScore,
                    technicalScore=tech.score,
                    volumeScore=volumeScore,
                    rsi=tech.rsi,
                    macd=tech.macd
                )
            }

            dao.upsertScores(out)
            Result.success()
        }catch(_:Exception){ Result.retry() }
    }
}
