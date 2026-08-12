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
        val batch=inputData.getInt("batch",35)
        val unknown=dao.unknownSymbols(batch,0)

        if(unknown.isEmpty()){
            prefs.edit().putString("status","نام نمادها کامل شد").putBoolean("running",false).apply()
            return Result.success()
        }

        prefs.edit().putBoolean("running",true)
            .putString("status","در حال تکمیل نام ${unknown.size} نماد").apply()

        var fixed=0
        for(s in unknown){
            try{
                val raw=api.instrumentInfoRaw(s.insCode)
                val o=api.jsonObjectFrom(raw,"instrumentInfo","instrument") ?: continue
                val symbol=cleanSymbol(firstString(o,"lVal18AFC","symbol","instrumentName"),s.insCode)
                val name=firstString(o,"lVal30","name","companyName","companyNamePersian")
                val flow=firstInt(o,"flow") ?: s.flow
                val board=firstString(o,"cgrValCotTitle","boardTitle","marketTitle") ?: s.boardTitle
                if(symbol!=null || !name.isNullOrBlank()){
                    dao.upsertSymbols(listOf(s.copy(
                        symbol=symbol ?: s.symbol,
                        name=name ?: s.name,
                        flow=flow,
                        segment=MarketPrefs.classify(flow,board),
                        boardTitle=board
                    )))
                    fixed++
                }
            }catch(_:Exception){}
            delay(80)
        }

        val remaining=dao.unknownSymbols(1,0).isNotEmpty()
        prefs.edit()
            .putString("status","نام نمادها: $fixed مورد این مرحله اصلاح شد")
            .putBoolean("running",remaining).apply()

        if(remaining){
            val next=OneTimeWorkRequestBuilder<MetadataWorker>()
                .setConstraints(HistoricalWorker.networkConstraint())
                .setInputData(workDataOf("batch" to batch))
                .setInitialDelay(3,TimeUnit.SECONDS).build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                CHAIN,ExistingWorkPolicy.APPEND_OR_REPLACE,next
            )
        }
        return Result.success()
    }

    companion object{ const val CHAIN="metadata_name_repair" }
}
