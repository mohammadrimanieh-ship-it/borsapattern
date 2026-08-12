package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class MetadataWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result{
        return try{
            val app=applicationContext as BorsaApp
            val dao=app.db.dao()
            val latest=dao.latestMarketDate() ?: return Result.success()
            val api=TsetmcClient()
            val arr=api.jsonArrayFrom(
                api.instrumentsHistoryInDayRaw(latest),
                "closingPriceDailyHistoryWithInstDetails"
            )
            val old=dao.allSymbols().associateBy{it.insCode}
            val out=mutableListOf<SymbolEntity>()
            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                val ins=firstString(o,"insCode","instrumentId")?:continue
                val symbol=cleanSymbol(firstString(o,"lVal18AFC","symbol"),ins)
                val name=firstString(o,"lVal30","name","companyNamePersian")
                val flow=firstInt(o,"flow")
                val board=firstString(
                    o,"cgrValCotTitle","boardTitle","boardname","markettypename","marketcategoryname"
                )
                val prev=old[ins]
                out += SymbolEntity(
                    insCode=ins,
                    symbol=symbol ?: prev?.symbol,
                    name=name ?: prev?.name,
                    flow=flow ?: prev?.flow,
                    segment=MarketPrefs.classify(flow ?: prev?.flow,board ?: prev?.boardTitle),
                    boardTitle=board ?: prev?.boardTitle
                )
            }
            if(out.isNotEmpty()) dao.upsertSymbols(out)
            Result.success(workDataOf("updated" to out.size))
        }catch(_:Exception){
            Result.retry()
        }
    }
}
