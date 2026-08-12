package com.borsapattern.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

object LiveScanEngine {
    suspend fun scanOnce(context:Context):Int=withContext(Dispatchers.IO){
        val app=context.applicationContext as BorsaApp
        val dao=app.db.dao()
        val api=TsetmcClient()
        val wanted=MarketPrefs.selected(context)
        val arr=api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
        val previous=dao.allSymbols().associateBy{it.insCode}

        data class Raw(
            val ins:String,val symbol:String?,val name:String?,val flow:Int?,val board:String?,
            val last:Double,val yesterday:Double,val volume:Double,val value:Double
        )

        val raws=mutableListOf<Raw>()
        for(i in 0 until arr.length()){
            val o=arr.optJSONObject(i)?:continue
            val ins=firstString(o,"insCode","instrumentId")?:continue
            val last=firstDouble(o,"pl","pDrCotVal","lastPrice")?:continue
            val y=firstDouble(o,"py","priceYesterday","yesterdayPrice")?:continue
            val vol=firstDouble(o,"qTotTran5J","volume")?:0.0
            val value=firstDouble(o,"qTotCap","value")?:0.0
            val rawSymbol=firstString(o,"lVal18AFC","symbol","instrumentName")
            val rawName=firstString(o,"lVal30","name","companyNamePersian")
            val flow=firstInt(o,"flow")
            val board=firstString(o,"cgrValCotTitle","boardTitle")
            val meta=previous[ins]
            val segment=meta?.segment ?: MarketPrefs.classify(flow,board)
            if(!wanted.contains(segment)) continue
            raws += Raw(ins,rawSymbol,rawName,flow,board,last,y,vol,value)
        }

        // کل بازار از یک MarketWatch خوانده می‌شود؛ تکنیکال عمیق فقط روی موارد قوی‌تر.
        val ranked=raws.map{r->
            val pm=if(r.yesterday>0) (((r.last/r.yesterday)-1.0).coerceAtLeast(0.0)/0.05).coerceIn(0.0,1.0) else 0.0
            val va=(r.volume/5_000_000.0).coerceIn(0.0,1.0)
            Triple(r,pm,va)
        }.sortedByDescending{(_,pm,va)->pm*0.6+va*0.4}

        val deep=ranked.take(90).associateBy{it.first.ins}
        val out=ArrayList<LiveScoreEntity>(ranked.size)

        for((r,pm,va) in ranked){
            val meta=SymbolResolver.ensure(
                dao,api,r.ins,r.symbol,r.name,r.flow,r.board
            )
            val display=meta.symbol ?: meta.name ?: cleanSymbol(r.symbol,r.ins)

            val pattern=PatternEngine.scoreLive(pm,va,0.35,0.25)
            val volume=(va*100.0).coerceIn(0.0,100.0)

            val tech=if(deep.containsKey(r.ins))
                TechnicalEngine.calculate(dao.recentDaily(r.ins,220),r.last)
            else TechnicalResult(40.0,null,null,null,null,"پایش سریع")

            // امتیاز شباهت رفتاری آزمایشی است و هویت شخص را اثبات نمی‌کند.
            val continuity=(1.0-abs(pm-va)).coerceIn(0.0,1.0)
            val actor=(pattern*0.45 + volume*0.35 + continuity*20.0).coerceIn(0.0,100.0)

            val finalScore=(
                pattern*0.40 +
                tech.score*0.25 +
                volume*0.20 +
                actor*0.15
            ).coerceIn(0.0,100.0)

            val reason="الگو ${pattern.toInt()} • تکنیکال ${tech.score.toInt()} • حجم ${volume.toInt()} • رفتار ${actor.toInt()}"

            out += LiveScoreEntity(
                insCode=r.ins,
                symbol=display,
                score=finalScore,
                reason=reason,
                updatedAt=System.currentTimeMillis(),
                patternScore=pattern,
                technicalScore=tech.score,
                volumeScore=volume,
                rsi=tech.rsi,
                macd=tech.macd,
                actorScore=actor,
                lastPrice=r.last
            )
        }

        dao.upsertScores(out)
        dao.repairLiveScoreNames()
        PaperTradingEngine.process(context,out)
        out.size
    }
}
