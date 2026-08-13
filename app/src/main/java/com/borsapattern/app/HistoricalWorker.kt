package com.borsapattern.app

import android.content.Context
import androidx.work.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.min

class HistoricalWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val api=TsetmcClient()
    private val dao get()=(applicationContext as BorsaApp).db.dao()
    private val prefs get()=applicationContext.getSharedPreferences("sync",Context.MODE_PRIVATE)

    override suspend fun doWork():Result{
        val offset=inputData.getInt("offset",0)
        val batchSize=inputData.getInt("batchSize",35)

        return try{
            // فقط اولین تکه، فهرست نمادها را تازه می‌کند.
            if(offset==0){
                prefs.edit()
                    .putString("sync_status","در حال به‌روزرسانی فهرست نمادها")
                    .putBoolean("sync_running",true)
                    .apply()

                val arr=api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
                val fresh=mutableListOf<SymbolEntity>()

                for(i in 0 until arr.length()){
                    val o=arr.optJSONObject(i)?:continue
                    val ins=firstString(o,"insCode","instrumentId")?:continue
                    val rawSymbol=firstString(o,"lVal18AFC","symbol","instrumentName")
                    val rawName=firstString(o,"lVal30","name","companyName","companyNamePersian")
                    fresh += SymbolEntity(
                        insCode=ins,
                        symbol=cleanSymbol(rawSymbol,ins),
                        name=rawName?.trim(),
                        flow=firstInt(o,"flow"),
                        segment=MarketPrefs.classify(
                            firstInt(o,"flow"),
                            firstString(o,"cgrValCotTitle","boardTitle")
                        ),
                        boardTitle=firstString(o,"cgrValCotTitle","boardTitle"),
                        instrumentType=MarketPrefs.classifyType(
                            cleanSymbol(rawSymbol,ins),
                            rawName?.trim(),
                            firstInt(o,"flow"),
                            firstString(o,"cgrValCotTitle","boardTitle")
                        )
                    )
                }
                if(fresh.isNotEmpty()) dao.upsertSymbols(fresh)
            }

            val wantedSegments=MarketPrefs.selectedSegments(applicationContext)
            val wantedTypes=MarketPrefs.selectedTypes(applicationContext)
            val symbols=dao.allSymbols().filter{
                wantedSegments.contains(it.segment) && wantedTypes.contains(it.instrumentType)
            }
            if(symbols.isEmpty()){
                prefs.edit()
                    .putString("sync_status","فهرست نمادها دریافت نشد؛ بعداً دوباره تلاش می‌شود")
                    .putBoolean("sync_running",false)
                    .apply()
                return Result.retry()
            }

            val total=symbols.size
            val start=offset.coerceIn(0,total)
            val end=min(start+batchSize,total)
            val cutoff=LocalDate.now().minusDays(370)

            prefs.edit()
                .putInt("sync_total",total)
                .putInt("sync_done",start)
                .putString("sync_status","دانلود تاریخچه در پس‌زمینه: $start از $total نماد")
                .putBoolean("sync_running",true)
                .apply()

            for(idx in start until end){
                val s=symbols[idx]
                try{
                    val latest=dao.latestDateFor(s.insCode)?:0
                    val d=api.jsonArrayFrom(api.dailyRaw(s.insCode),"closingPriceDaily")
                    val rows=mutableListOf<DailyEntity>()

                    for(j in 0 until d.length()){
                        val o=d.optJSONObject(j)?:continue
                        val date=firstInt(o,"dEven","date")?:continue
                        if(date<=latest) continue

                        val parsed=runCatching{
                            LocalDate.parse(date.toString(),DateTimeFormatter.BASIC_ISO_DATE)
                        }.getOrNull()?:continue
                        if(parsed.isBefore(cutoff)) continue

                        rows += DailyEntity(
                            insCode=s.insCode,
                            date=date,
                            high=firstDouble(o,"priceMax","pmax","pMax"),
                            last=firstDouble(o,"pDrCotVal","pl","lastPrice"),
                            yesterday=firstDouble(o,"priceYesterday","py","yesterdayPrice"),
                            volume=firstDouble(o,"qTotTran5J","volume"),
                            value=firstDouble(o,"qTotCap","value")
                        )
                    }
                    if(rows.isNotEmpty()) dao.upsertDaily(rows)
                }catch(_:Exception){
                    // خطای یک نماد نباید بقیه دانلود را متوقف کند.
                }

                prefs.edit()
                    .putInt("sync_done",idx+1)
                    .putString("sync_status","دانلود تاریخچه در پس‌زمینه: ${idx+1} از $total نماد")
                    .apply()

                setProgress(workDataOf(
                    "stage" to "همگام‌سازی ${s.symbol ?: s.name ?: "نماد"}",
                    "done" to idx+1,
                    "total" to total
                ))
            }

            if(end<total){
                // ادامه کار به یک Worker کوتاه دیگر سپرده می‌شود.
                // این زنجیره بعد از خروج از اپ هم در WorkManager باقی می‌ماند.
                val next=OneTimeWorkRequestBuilder<HistoricalWorker>()
                    .setConstraints(networkConstraint())
                    .setInputData(workDataOf(
                        "offset" to end,
                        "batchSize" to batchSize
                    ))
                    .setInitialDelay(2,TimeUnit.SECONDS)
                    .build()

                WorkManager.getInstance(applicationContext)
                    .enqueueUniqueWork(
                        HISTORY_CHAIN,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        next
                    )
                return Result.success()
            }

            PatternEngine.seedInitialEvents((applicationContext as BorsaApp).db)

            prefs.edit()
                .putLong("last_sync",System.currentTimeMillis())
                .putInt("sync_done",total)
                .putInt("sync_total",total)
                .putBoolean("sync_running",false)
                .putString("sync_status","همگام‌سازی تاریخچه کامل شد")
                .apply()

            Result.success()
        }catch(e:Exception){
            prefs.edit()
                .putString("sync_status","اتصال قطع شد؛ WorkManager دوباره تلاش می‌کند")
                .putBoolean("sync_running",true)
                .apply()
            Result.retry()
        }
    }

    companion object{
        const val HISTORY_CHAIN="history_sync_chain"

        fun start(context:Context, replace:Boolean=false){
            val req=OneTimeWorkRequestBuilder<HistoricalWorker>()
                .setConstraints(networkConstraint())
                .setInputData(workDataOf("offset" to 0,"batchSize" to 35))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                HISTORY_CHAIN,
                if(replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                req
            )
        }

        fun networkConstraint():Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
    }
}
