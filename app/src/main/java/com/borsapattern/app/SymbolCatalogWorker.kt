package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SymbolCatalogWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val dao get()=(applicationContext as BorsaApp).db.dao()
    private val api=TsetmcClient()
    private val prefs get()=applicationContext.getSharedPreferences("catalog",Context.MODE_PRIVATE)

    override suspend fun doWork():Result{
        return try{
            prefs.edit()
                .putBoolean("running",true)
                .putString("status","در حال آماده‌سازی فهرست نمادها")
                .apply()

            val arr=api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
            if(arr.length()==0){
                prefs.edit()
                    .putBoolean("running",false)
                    .putString("status","فهرست بازار دریافت نشد")
                    .apply()
                return Result.success()
            }

            val existing=dao.allSymbols().associateBy{it.insCode}
            val fresh=ArrayList<SymbolEntity>(arr.length())

            for(i in 0 until arr.length()){
                val o=arr.optJSONObject(i)?:continue
                val ins=firstString(o,"insCode","instrumentId")?:continue
                val old=existing[ins]

                val rawSymbol=firstString(o,"lVal18AFC","symbol","instrumentName")
                val rawName=firstString(o,"lVal30","name","companyName","companyNamePersian")
                val symbol=cleanSymbol(rawSymbol,ins)?.takeIf{it.isNotBlank()} ?: old?.symbol
                val name=rawName?.trim()?.takeIf{it.isNotBlank()} ?: old?.name
                val flow=firstInt(o,"flow") ?: old?.flow
                val board=firstString(o,"cgrValCotTitle","boardTitle") ?: old?.boardTitle

                val segment=MarketPrefs.classify(flow,board).let{derived->
                    if(derived==MarketPrefs.OTHER) old?.segment ?: derived else derived
                }
                val type=MarketPrefs.classifyType(symbol,name,flow,board)

                fresh += SymbolEntity(
                    insCode=ins,
                    symbol=symbol,
                    name=name,
                    flow=flow,
                    segment=segment,
                    boardTitle=board,
                    instrumentType=type
                )
            }

            if(fresh.isNotEmpty()) dao.upsertSymbols(fresh)
            dao.repairLiveScoreNames()

            val eligible=dao.allSymbols().count{
                val t=MarketPrefs.classifyType(it.symbol,it.name,it.flow,it.boardTitle)
                val seg=MarketPrefs.classify(it.flow,it.boardTitle).let{d->
                    if(d==MarketPrefs.OTHER) it.segment else d
                }
                MarketPrefs.isSignalUniverse(seg,t,it.symbol,it.name)
            }

            prefs.edit()
                .putBoolean("running",false)
                .putLong("last_refresh",System.currentTimeMillis())
                .putInt("eligible_count",eligible)
                .putString("status","فهرست نمادها آماده شد: $eligible نماد قابل تحلیل")
                .apply()

            Result.success()
        }catch(e:Exception){
            prefs.edit()
                .putBoolean("running",false)
                .putString("status","خطای فهرست نمادها: ${e.message ?: "نامشخص"}")
                .apply()
            Result.success()
        }
    }

    companion object{ const val CHAIN="symbol_catalog_refresh" }
}
