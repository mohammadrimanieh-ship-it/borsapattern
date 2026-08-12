package com.borsapattern.app

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class MetadataWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    private val dao get()=(applicationContext as BorsaApp).db.dao()
    private val api=TsetmcClient()
    private val prefs get()=applicationContext.getSharedPreferences("metadata",Context.MODE_PRIVATE)

    override suspend fun doWork():Result{
        val batch=inputData.getInt("batch",30)
        val symbols=dao.unknownSymbols(batch)
        val live=dao.liveScoresNeedingName(batch)

        if(symbols.isEmpty() && live.isEmpty()){
            dao.repairLiveScoreNames()
            prefs.edit()
                .putString("status","نام نمادها کامل شد")
                .putBoolean("running",false)
                .apply()
            return Result.success()
        }

        prefs.edit()
            .putBoolean("running",true)
            .putString("status","در حال ترمیم نام نمادها")
            .apply()

        val codes=LinkedHashSet<String>()
        symbols.forEach{codes+=it.insCode}
        live.forEach{codes+=it.insCode}

        var fixed=0
        for(code in codes.take(batch)){
            try{
                val current=dao.symbolByCode(code)
                val entity=SymbolResolver.ensure(
                    dao=dao,api=api,insCode=code,
                    rawSymbol=current?.symbol,rawName=current?.name,
                    flow=current?.flow,board=current?.boardTitle
                )
                if(!entity.symbol.isNullOrBlank() || !entity.name.isNullOrBlank()) fixed++
            }catch(_:Exception){}
            delay(90)
        }

        dao.repairLiveScoreNames()

        prefs.edit()
            .putString("status","این مرحله $fixed نام اصلاح شد؛ ادامه در پس‌زمینه")
            .putBoolean("running",true)
            .apply()

        val next=OneTimeWorkRequestBuilder<MetadataWorker>()
            .setConstraints(HistoricalWorker.networkConstraint())
            .setInputData(workDataOf("batch" to batch))
            .setInitialDelay(3,TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            CHAIN,ExistingWorkPolicy.APPEND_OR_REPLACE,next
        )
        return Result.success()
    }

    companion object{ const val CHAIN="metadata_name_repair" }
}
