package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class HistoricalWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val api=TsetmcClient(); private val dao get()=(applicationContext as BorsaApp).db.dao()
    override suspend fun doWork():Result=try{
        setProgress(workDataOf("stage" to "به‌روزرسانی فهرست نمادها","progress" to 1))
        val arr=api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
        val fresh=mutableListOf<SymbolEntity>()
        for(i in 0 until arr.length()){
            val o=arr.optJSONObject(i)?:continue; val ins=firstString(o,"insCode","instrumentId")?:continue
            fresh += SymbolEntity(ins,firstString(o,"lVal18AFC","symbol"),firstString(o,"lVal30","name"))
        }
        if(fresh.isNotEmpty()) dao.upsertSymbols(fresh)
        val symbols=dao.allSymbols(); val cutoff=LocalDate.now().minusDays(370)
        symbols.forEachIndexed { idx,s ->
            setProgress(workDataOf("stage" to "همگام‌سازی ${s.symbol?:s.insCode}","progress" to ((idx+1)*80/maxOf(1,symbols.size))))
            try{
                val latest=dao.latestDateFor(s.insCode)?:0
                val d=api.jsonArrayFrom(api.dailyRaw(s.insCode),"closingPriceDaily")
                val batch=mutableListOf<DailyEntity>()
                for(j in 0 until d.length()){
                    val o=d.optJSONObject(j)?:continue; val date=firstInt(o,"dEven","date")?:continue
                    if(date<=latest) continue
                    val parsed=runCatching{LocalDate.parse(date.toString(),DateTimeFormatter.BASIC_ISO_DATE)}.getOrNull()?:continue
                    if(parsed.isBefore(cutoff)) continue
                    batch += DailyEntity(s.insCode,date,firstDouble(o,"priceMax","pmax","pMax"),firstDouble(o,"pDrCotVal","pl","lastPrice"),firstDouble(o,"priceYesterday","py","yesterdayPrice"),firstDouble(o,"qTotTran5J","volume"),firstDouble(o,"qTotCap","value"))
                }
                if(batch.isNotEmpty()) dao.upsertDaily(batch)
            }catch(_:Exception){}
        }
        setProgress(workDataOf("stage" to "ساخت کاندیدهای جدید","progress" to 90))
        PatternEngine.seedInitialEvents((applicationContext as BorsaApp).db)
        applicationContext.getSharedPreferences("sync",Context.MODE_PRIVATE).edit().putLong("last_sync",System.currentTimeMillis()).apply()
        Result.success(workDataOf("stage" to "همگام‌سازی کامل شد","progress" to 100))
    }catch(e:Exception){Result.retry()}
}

fun firstString(o:JSONObject,vararg keys:String):String?=keys.firstNotNullOfOrNull{k->if(o.has(k)&&!o.isNull(k))o.optString(k,null)else null}
fun firstInt(o:JSONObject,vararg keys:String):Int?=keys.firstNotNullOfOrNull{k->if(o.has(k)&&!o.isNull(k))o.optInt(k)else null}
fun firstDouble(o:JSONObject,vararg keys:String):Double?=keys.firstNotNullOfOrNull{k->if(o.has(k)&&!o.isNull(k))o.optDouble(k)else null}
