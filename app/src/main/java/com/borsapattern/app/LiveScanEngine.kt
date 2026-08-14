package com.borsapattern.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap

object LiveScanEngine {
    private val lastQueueCheck=ConcurrentHashMap<String,Long>()
    suspend fun scanOnce(context:Context):Int=withContext(Dispatchers.IO){
        val app=context.applicationContext as BorsaApp
        val dao=app.db.dao()
        val api=TsetmcClient()
        val livePrefs=context.getSharedPreferences("live_monitor",Context.MODE_PRIVATE)
        val today=MarketClock.todayGregorianInt()

        if(!MarketClock.isLiveWindow()){
            livePrefs.edit()
                .putString("market_status",MarketClock.phase())
                .putLong("last_attempt",System.currentTimeMillis())
                .apply()
            return@withContext 0
        }

        val arr=api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
        val previous=dao.allSymbols().associateBy{it.insCode}
        val previousLive=dao.liveScoresForSession(today).associateBy{it.insCode}

        data class Raw(
            val ins:String,val symbol:String?,val name:String?,val flow:Int?,val board:String?,
            val last:Double,val high:Double,val yesterday:Double,val volume:Double,val value:Double,
            val marketDate:Int
        )

        val raws=mutableListOf<Raw>()
        var currentRows=0
        for(i in 0 until arr.length()){
            val o=arr.optJSONObject(i)?:continue
            val marketDate=firstInt(o,"dEven","date") ?: continue

            // Critical holiday/stale-data guard: live signals are only allowed
            // when the MarketWatch row belongs to today's Gregorian session.
            if(marketDate!=today) continue
            currentRows++

            val ins=firstString(o,"insCode","instrumentId")?:continue
            val last=firstDouble(o,"pl","pDrCotVal","lastPrice")?:continue
            val high=firstDouble(o,"pMax","priceMax","pmax") ?: last
            val y=firstDouble(o,"py","priceYesterday","yesterdayPrice")?:continue
            val vol=firstDouble(o,"qTotTran5J","volume")?:0.0
            val value=firstDouble(o,"qTotCap","value")?:0.0
            val rawSymbol=firstString(o,"lVal18AFC","symbol","instrumentName")
            val rawName=firstString(o,"lVal30","name","companyNamePersian")
            val flow=firstInt(o,"flow")
            val board=firstString(o,"cgrValCotTitle","boardTitle")
            val meta=previous[ins]
            val segment=meta?.segment ?: MarketPrefs.classify(flow,board)
            val type=meta?.instrumentType ?: MarketPrefs.classifyType(
                cleanSymbol(rawSymbol,ins),rawName,flow,board
            )

            val eventTime=firstInt(o,"hEven","time")
            if(eventTime==null || eventTime !in 90000..123000) continue

            val signalName=meta?.name ?: rawName
            val signalSymbol=meta?.symbol ?: cleanSymbol(rawSymbol,ins)
            if(!MarketPrefs.isSignalUniverse(segment,type,signalSymbol,signalName)) continue

            raws += Raw(
                ins,rawSymbol,rawName,flow,board,last,high,y,vol,value,marketDate
            )
        }

        if(currentRows==0){
            // Official holidays can fall on normal weekdays. If there are no
            // current-date MarketWatch rows, do not recycle stale scores.
            dao.clearOldLiveScores(today)
            livePrefs.edit()
                .putString("market_status","امروز داده زنده بازار دریافت نشد")
                .putLong("last_attempt",System.currentTimeMillis())
                .apply()
            return@withContext 0
        }

        dao.clearOldLiveScores(today)

        val ranked=raws.map{r->
            val positiveMove=if(r.yesterday>0)
                (((r.last/r.yesterday)-1.0).coerceAtLeast(0.0)/0.05).coerceIn(0.0,1.0)
            else 0.0
            val nearHigh=if(r.high>0)
                ((r.last/r.high)-0.965).div(0.035).coerceIn(0.0,1.0)
            else 0.0
            val va=(r.volume/5_000_000.0).coerceIn(0.0,1.0)
            val quick=(positiveMove*0.45 + nearHigh*0.35 + va*0.20)
            Triple(r,quick,va)
        }.sortedByDescending{it.second}

        // The entire market receives a cheap pass. Deep technical work is only
        // done for the upper adaptive slice.
        val deepCount=(ranked.size/10).coerceIn(45,80)
        val deep=ranked.take(deepCount).associateBy{it.first.ins}
        val out=ArrayList<LiveScoreEntity>(ranked.size)
        val dailyOut=ArrayList<DailyEntity>(ranked.size)
        val symbolUpdates=ArrayList<SymbolEntity>()
        var strongest=0.0

        for((rankedIndex,item) in ranked.withIndex()){
            val (r,quick,va)=item
            val oldMeta=previous[r.ins]
            val display=oldMeta?.symbol
                ?: cleanSymbol(r.symbol,r.ins)
                ?: oldMeta?.name
                ?: r.name
                ?: r.ins

            // MarketWatch metadata is merged locally. Network metadata repair
            // runs separately and never blocks the live scan.
            val rawSymbol=cleanSymbol(r.symbol,r.ins)
            val localName=r.name?.trim()?.takeIf{it.isNotBlank()}
            val derivedSegment=MarketPrefs.classify(r.flow,r.board)
            val segment=when{
                derivedSegment!=MarketPrefs.OTHER -> derivedSegment
                oldMeta!=null -> oldMeta.segment
                else -> MarketPrefs.OTHER
            }
            val localType=MarketPrefs.classifyType(
                rawSymbol ?: oldMeta?.symbol,
                localName ?: oldMeta?.name,
                r.flow ?: oldMeta?.flow,
                r.board ?: oldMeta?.boardTitle
            )
            val merged=SymbolEntity(
                insCode=r.ins,
                symbol=rawSymbol ?: oldMeta?.symbol,
                name=localName ?: oldMeta?.name,
                flow=r.flow ?: oldMeta?.flow,
                segment=segment,
                boardTitle=r.board ?: oldMeta?.boardTitle,
                instrumentType=localType
            )
            if(merged!=oldMeta) symbolUpdates += merged

            val pattern=PatternEngine.scoreLive(quick,va,0.35,0.25)
            val volume=(va*100.0).coerceIn(0.0,100.0)

            val tech=if(deep.containsKey(r.ins))
                TechnicalEngine.calculate(dao.recentDaily(r.ins,220),r.last)
            else TechnicalResult(40.0,null,null,null,null,"پایش سریع")

            val continuity=(1.0-abs(quick-va)).coerceIn(0.0,1.0)
            val actor=(pattern*0.45 + volume*0.35 + continuity*20.0)
                .coerceIn(0.0,100.0)

            val now=MarketClock.now()
            val nowCode=now.hour*10000 + now.minute*100 + now.second
            val learnedTimeBoost=QueuePatternLearningEngine.liveTimeBoost(context,nowCode)
            val persistenceBoost=QueuePatternLearningEngine.advancedPersistenceBoost(
                context,nowCode,pattern,tech.score,volume
            )
            val learnedBoost=(learnedTimeBoost+persistenceBoost).coerceIn(-10.0,10.0)

            val finalScore=(
                pattern*0.40 +
                tech.score*0.25 +
                volume*0.20 +
                actor*0.15 +
                learnedBoost
            ).coerceIn(0.0,100.0)

            val level=when{
                finalScore>=85.0 -> "STRONG"
                finalScore>=70.0 -> "EARLY"
                finalScore>=55.0 -> "WATCH"
                else -> "NONE"
            }
            strongest=maxOf(strongest,finalScore)

            val old=previousLive[r.ins]
            val sameSession=old?.sessionDate==today
            val firstAlert=when{
                level=="STRONG" || level=="EARLY" ->
                    if(sameSession) old?.firstAlertAt ?: System.currentTimeMillis()
                    else System.currentTimeMillis()
                else -> if(sameSession) old?.firstAlertAt else null
            }

            val reason=
                "الگو ${pattern.toInt()} • تکنیکال ${tech.score.toInt()} • حجم ${volume.toInt()} • رفتار ${actor.toInt()}" +
                if(kotlin.math.abs(learnedBoost)>=0.5)
                    " • الگوی ماندگاری ${if(learnedBoost>0) "+" else ""}${learnedBoost.toInt()}"
                else ""

            var queueDetectedAt=if(sameSession) old?.queueDetectedAt else null
            var leadSeconds=if(sameSession) old?.leadSeconds else null
            val nowMs=System.currentTimeMillis()
            val lastCheck=lastQueueCheck[r.ins] ?: 0L
            if(
                queueDetectedAt==null &&
                (level=="EARLY" || level=="STRONG") &&
                rankedIndex<15 &&
                deep.containsKey(r.ins) &&
                nowMs-lastCheck>=15_000L
            ){
                lastQueueCheck[r.ins]=nowMs
                val isQueue=runCatching{
                    detectQueueNow(api,r.ins,today,r.high)
                }.getOrDefault(false)
                if(isQueue){
                    queueDetectedAt=nowMs
                    leadSeconds=firstAlert?.let{
                        ((nowMs-it)/1000L).coerceAtLeast(0L).toInt()
                    }
                }
            }

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
                lastPrice=r.last,
                firstAlertAt=firstAlert,
                alertLevel=level,
                sessionDate=today,
                queueDetectedAt=queueDetectedAt,
                leadSeconds=leadSeconds
            )

            // Incremental daily storage: today's row is continuously upserted.
            // No multi-year history download is needed on normal trading days.
            dailyOut += DailyEntity(
                insCode=r.ins,
                date=today,
                high=r.high,
                last=r.last,
                yesterday=r.yesterday,
                volume=r.volume,
                value=r.value
            )
        }

        if(symbolUpdates.isNotEmpty()) dao.upsertSymbols(symbolUpdates)
        if(dailyOut.isNotEmpty()) dao.upsertDaily(dailyOut)
        dao.upsertScores(out)
        dao.repairLiveScoreNames()
        PaperTradingEngine.process(context,out)

        livePrefs.edit()
            .putString("market_status","بازار باز")
            .putLong("last_scan",System.currentTimeMillis())
            .putFloat("strongest_score",strongest.toFloat())
            .putInt("live_count",out.count{it.alertLevel!="NONE"})
            .apply()
        out.size
    }

    private fun detectQueueNow(
        api:TsetmcClient,
        insCode:String,
        date:Int,
        high:Double
    ):Boolean{
        if(high<=0) return false
        val arr=api.jsonArrayFrom(
            api.bestLimitsRaw(insCode,date),
            "bestLimitsHistory","bestLimits"
        )
        var latestTime=-1
        var latestQueue=false
        for(i in 0 until arr.length()){
            val o=arr.optJSONObject(i)?:continue
            if((firstInt(o,"number","level")?:1)!=1) continue
            val t=firstInt(o,"hEven","time")?:continue
            if(t !in 90000..123000 || t<latestTime) continue
            val bp=firstDouble(o,"pMeDem","bidPrice")?:continue
            val bv=firstDouble(o,"qTitMeDem","bidVolume")?:0.0
            val av=firstDouble(o,"qTitMeOf","askVolume")?:0.0
            val imb=if(bv+av>0) bv/(bv+av) else 0.0
            latestTime=t
            latestQueue=
                bv>0 &&
                bp>=high*0.9995 &&
                (av<=0.0 || imb>=0.92)
        }
        return latestQueue
    }

    suspend fun recommendedDelayMillis(context:Context):Long{
        val app=context.applicationContext as BorsaApp
        val scores=runCatching{app.db.dao().topSignalScores()}.getOrDefault(emptyList())
        val max=scores.maxOfOrNull{it.score} ?: 0.0
        return when{
            max>=85 -> 5_000L
            max>=70 -> 10_000L
            else -> 25_000L
        }
    }
}
