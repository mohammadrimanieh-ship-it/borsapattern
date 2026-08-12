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
        val net=HistoricalWorker.networkConstraint()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_incremental_sync_kickoff",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncKickoffWorker>(12,TimeUnit.HOURS)
                .setConstraints(net)
                .build()
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "historical_queue_analysis",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<QueueAnalysisWorker>(1,TimeUnit.HOURS)
                .setConstraints(net)
                .build()
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "live_monitor",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LiveWorker>(15,TimeUnit.MINUTES)
                .setConstraints(net)
                .build()
        )

        // اگر دانلود قبلی نیمه‌کاره بوده، با بازشدن اپ ادامه‌اش را تضمین می‌کنیم.
        val prefs=getSharedPreferences("sync",MODE_PRIVATE)
        if(prefs.getBoolean("sync_running",false)){
            HistoricalWorker.start(this,replace=false)
        }
    }
}
