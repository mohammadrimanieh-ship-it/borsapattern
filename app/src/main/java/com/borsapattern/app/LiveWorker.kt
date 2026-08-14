package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LiveWorker(ctx:Context,p:WorkerParameters):CoroutineWorker(ctx,p){
    override suspend fun doWork():Result{
        return try{
            val enabled=applicationContext.getSharedPreferences(
                MarketMonitorService.PREFS,Context.MODE_PRIVATE
            ).getBoolean("background_enabled",false)

            if(enabled && MarketClock.isLiveWindow()){
                runCatching{MarketMonitorService.start(applicationContext)}
                // Also perform one immediate scan; the foreground service then
                // continues with adaptive 3/7/25-second intervals.
                LiveScanEngine.scanOnce(applicationContext)
            }
            Result.success()
        }catch(_:Exception){
            Result.retry()
        }
    }
}
