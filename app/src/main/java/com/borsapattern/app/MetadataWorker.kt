package com.borsapattern.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MetadataWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val dao get()=(applicationContext as BorsaApp).db.dao()
    private val api=TsetmcClient()
    private val prefs get()=applicationContext.getSharedPreferences("metadata",Context.MODE_PRIVATE)
    private val catalogPrefs get()=applicationContext.getSharedPreferences("catalog",Context.MODE_PRIVATE)

    override suspend fun doWork():Result{
        val batch=inputData.getInt("batch",60).coerceIn(20,120)
        val round=inputData.getInt("round",0)

        // One MarketWatch request can repair hundreds of names/flows at once.
        if(round==0 || round%12==0){
            runCatching{bulkRepairFromMarketWatch()}
        }

        val symbols=dao.symbolsNeedingMetadata(batch)
        val live=dao.liveScoresNeedingName(batch)

        if(symbols.isEmpty() && live.isEmpty()){
            dao.repairLiveScoreNames()
            prefs.edit()
                .putString("status","نام و بازار نمادها کامل شد")
                .putBoolean("running",false)
                .apply()

            val catalog=OneTimeWorkRequestBuilder<SymbolCatalogWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInputData(workDataOf("finalizeOnly" to true))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                SymbolCatalogWorker.CHAIN,
                ExistingWorkPolicy.REPLACE,
                catalog
            )
            return Result.success()
        }

        prefs.edit()
            .putBoolean("running",true)
            .putString("status","در حال تکمیل نام و بازار نمادها")
            .apply()
        catalogPrefs.edit()
            .putBoolean("running",true)
            .putString("status","در حال تکمیل خودکار نام و بازار نمادها")
            .apply()

        val codes=LinkedHashSet<String>()
        symbols.forEach{codes+=it.insCode}
        live.forEach{codes+=it.insCode}

        val fixed=AtomicInteger(0)
        coroutineScope{
            for(chunk in codes.take(batch).chunked(10)){
                chunk.map{code->
                    async(Dispatchers.IO){
                        try{
                            val current=dao.symbolByCode(code)
                            val entity=SymbolResolver.ensure(
                                dao=dao,api=api,insCode=code,
                                rawSymbol=current?.symbol,rawName=current?.name,
                                flow=current?.flow,board=current?.boardTitle
                            )
                            if(
                                !entity.symbol.isNullOrBlank() ||
                                !entity.name.isNullOrBlank()
                            ) fixed.incrementAndGet()
                        }catch(_:Exception){}
                    }
                }.awaitAll()
                yield()
            }
        }

        dao.repairLiveScoreNames()

        val remaining=dao.symbolsNeedingMetadata(1).isNotEmpty()
        if(!remaining || round>=39){
            dao.repairLiveScoreNames()
            prefs.edit()
                .putString(
                    "status",
                    if(remaining) "تکمیل متادیتا متوقف شد؛ بعضی نمادها از منبع بازار اطلاعات کافی ندارند"
                    else "نام و بازار نمادها کامل شد"
                )
                .putBoolean("running",false)
                .apply()

            val catalog=OneTimeWorkRequestBuilder<SymbolCatalogWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInputData(workDataOf("finalizeOnly" to true))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                SymbolCatalogWorker.CHAIN,
                ExistingWorkPolicy.REPLACE,
                catalog
            )
            return Result.success()
        }

        prefs.edit()
            .putString("status","این مرحله ${fixed.get()} نماد تکمیل شد؛ ادامه طبقه‌بندی در پس‌زمینه")
            .putBoolean("running",true)
            .apply()
        catalogPrefs.edit()
            .putString("status","تکمیل خودکار نمادها — مرحله ${round+1}، این مرحله ${fixed.get()} مورد")
            .putBoolean("running",true)
            .apply()

        val next=OneTimeWorkRequestBuilder<MetadataWorker>()
            .setConstraints(HistoricalWorker.networkConstraint())
            .setInputData(workDataOf("batch" to batch,"round" to round+1))
            .setInitialDelay(1,TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            CHAIN,ExistingWorkPolicy.APPEND_OR_REPLACE,next
        )
        return Result.success()
    }

    private suspend fun bulkRepairFromMarketWatch(){
        val arr=api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
        if(arr.length()==0) return
        val old=dao.allSymbols().associateBy{it.insCode}
        val updates=mutableListOf<SymbolEntity>()

        for(i in 0 until arr.length()){
            val o=arr.optJSONObject(i)?:continue
            val ins=firstString(o,"insCode","instrumentId")?:continue
            val current=old[ins] ?: continue

            val rawSymbol=firstString(o,"lVal18AFC","symbol","instrumentName")
            val symbol=cleanSymbol(rawSymbol,ins)
                ?.takeIf{it.isNotBlank() && it!=ins}
                ?: current.symbol
            val name=firstString(o,"lVal30","name","companyNamePersian")
                ?.takeIf{it.isNotBlank()}
                ?: current.name
            val flow=firstInt(o,"flow") ?: current.flow
            val board=firstString(o,"cgrValCotTitle","boardTitle")
                ?.takeIf{it.isNotBlank()}
                ?: current.boardTitle

            val derivedSegment=MarketPrefs.classify(flow,board)
            val segment=if(derivedSegment!=MarketPrefs.OTHER)
                derivedSegment else current.segment
            val type=MarketPrefs.classifyType(symbol,name,flow,board)

            if(
                symbol!=current.symbol || name!=current.name ||
                flow!=current.flow || board!=current.boardTitle ||
                segment!=current.segment || type!=current.instrumentType
            ){
                updates += current.copy(
                    symbol=symbol,name=name,flow=flow,
                    segment=segment,boardTitle=board,instrumentType=type
                )
            }
        }
        if(updates.isNotEmpty()) dao.upsertSymbols(updates)
    }

    companion object{ const val CHAIN="metadata_name_repair" }
}
