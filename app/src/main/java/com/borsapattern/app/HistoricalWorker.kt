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
        val confirmed=inputData.getBoolean("userConfirmed",false)

        if(!confirmed){
            prefs.edit()
                .putBoolean("sync_running",false)
                .putString("sync_status","منتظر تایید شما برای شروع استخراج")
                .apply()
            return Result.success()
        }

        return try{
            // فقط اولین تکه، فهرست نمادها را تازه می‌کند.
            if(offset==0){
                prefs.edit()
                    .putString("sync_status","در حال به‌روزرسانی فهرست نمادها")
                    .putBoolean("sync_running",true)
                    .apply()

                val arr=api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
                val existing=dao.allSymbols().associateBy{it.insCode}
                val fresh=mutableListOf<SymbolEntity>()

                for(i in 0 until arr.length()){
                    val o=arr.optJSONObject(i)?:continue
                    val ins=firstString(o,"insCode","instrumentId")?:continue
                    val rawSymbol=firstString(o,"lVal18AFC","symbol","instrumentName")
                    val rawName=firstString(o,"lVal30","name","companyName","companyNamePersian")
                    val old=existing[ins]
                    val clean=cleanSymbol(rawSymbol,ins)
                    val resolvedSymbol=clean ?: old?.symbol
                    val resolvedName=rawName?.trim()?.takeIf{it.isNotBlank()} ?: old?.name
                    val flow=firstInt(o,"flow") ?: old?.flow
                    val board=firstString(o,"cgrValCotTitle","boardTitle") ?: old?.boardTitle
                    fresh += SymbolEntity(
                        insCode=ins,
                        symbol=resolvedSymbol,
                        name=resolvedName,
                        flow=flow,
                        segment=MarketPrefs.classify(flow,board).let{
                            if(it==MarketPrefs.OTHER) old?.segment ?: it else it
                        },
                        boardTitle=board,
                        instrumentType=MarketPrefs.classifyType(
                            resolvedSymbol,resolvedName,flow,board
                        )
                    )
                }
                if(fresh.isNotEmpty()) dao.upsertSymbols(fresh)
            }

            val wantedSegments=MarketPrefs.selectedSegments(applicationContext)
            val wantedTypes=MarketPrefs.selectedTypes(applicationContext)
            val extractPrefs=applicationContext.getSharedPreferences("extract",Context.MODE_PRIVATE)
            val years=extractPrefs.getInt("years",5).coerceIn(1,5)
            val allStored=dao.allSymbols()
            val symbols=allStored.filter{
                val effectiveType=MarketPrefs.classifyType(
                    it.symbol,it.name,it.flow,it.boardTitle
                )
                val effectiveSegment=MarketPrefs.classify(it.flow,it.boardTitle).let{derived->
                    when{
                        derived!=MarketPrefs.OTHER -> derived
                        it.segment!=MarketPrefs.OTHER -> it.segment
                        else -> MarketPrefs.OTHER
                    }
                }

                val supportedType =
                    effectiveType==MarketPrefs.TYPE_STOCK ||
                    effectiveType==MarketPrefs.TYPE_BASE ||
                    (
                        effectiveType==MarketPrefs.TYPE_FUND &&
                        MarketPrefs.isLeveragedFund(it.symbol,it.name)
                    )

                val segmentAllowed =
                    effectiveSegment!=MarketPrefs.OTHER &&
                    wantedSegments.contains(effectiveSegment)

                segmentAllowed &&
                wantedTypes.contains(effectiveType) &&
                supportedType &&
                !it.symbol.isNullOrBlank()
            }

            if(symbols.isEmpty()){
                prefs.edit()
                    .putInt("sync_total",0)
                    .putInt("sync_done",0)
                    .putString(
                        "sync_status",
                        "Universe معتبر صفر شد؛ ${allStored.size} رکورد نماد در دیتابیس هست ولی هیچ‌کدام با فیلتر فعلی منطبق نشد"
                    )
                    .putBoolean("sync_running",false)
                    .apply()
                return Result.success()
            }

            val total=symbols.size
            val start=offset.coerceIn(0,total)
            val end=min(start+batchSize,total)
            val cutoff=LocalDate.now().minusDays((years*366L)+10L)

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
                        "batchSize" to batchSize,
                        "userConfirmed" to true
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
                .putString("sync_status","تاریخچه کامل شد؛ تحلیل صف‌های معتبر شروع شد")
                .apply()

            val analysis=OneTimeWorkRequestBuilder<QueueAnalysisWorker>()
                .setConstraints(networkConstraint())
                .setInputData(workDataOf(
                    "batchSize" to 160,
                    "parallelism" to 4,
                    "resetErrors" to true
                ))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                QueueAnalysisWorker.ANALYSIS_CHAIN,
                ExistingWorkPolicy.REPLACE,
                analysis
            )

            Result.success()
        }catch(e:Exception){
            prefs.edit()
                .putString(
                    "sync_status",
                    "خطای دریافت داده: ${e.message ?: "نامشخص"} — دوباره «شروع استخراج» را بزنید"
                )
                .putBoolean("sync_running",false)
                .apply()
            Result.success()
        }
    }

    companion object{
        const val HISTORY_CHAIN="history_sync_chain"

        fun start(context:Context, replace:Boolean=false){
            val req=OneTimeWorkRequestBuilder<HistoricalWorker>()
                .setConstraints(networkConstraint())
                .setInputData(workDataOf(
                    "offset" to 0,
                    "batchSize" to 35,
                    "userConfirmed" to true
                ))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                HISTORY_CHAIN,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun networkConstraint():Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
    }
}
