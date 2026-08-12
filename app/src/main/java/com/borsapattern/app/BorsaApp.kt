package com.borsapattern.app

import android.app.Application
import androidx.room.Room
import androidx.work.*
import java.util.concurrent.TimeUnit

class BorsaApp:Application(){
    lateinit var db:AppDatabase
    override fun onCreate(){
        super.onCreate()
        db=Room.databaseBuilder(this,AppDatabase::class.java,"borsa.db").build()
        Notifications.createChannel(this)
        scheduleBackgroundWork()
    }
    private fun scheduleBackgroundWork(){
        val net=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_incremental_sync",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HistoricalWorker>(12,TimeUnit.HOURS).setConstraints(net).build())
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "historical_queue_analysis",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<QueueAnalysisWorker>(1,TimeUnit.HOURS).setConstraints(net).build())
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "live_monitor",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LiveWorker>(15,TimeUnit.MINUTES).setConstraints(net).build())
    }
}
