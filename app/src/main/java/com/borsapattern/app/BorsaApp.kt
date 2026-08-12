package com.borsapattern.app

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.*
import java.util.concurrent.TimeUnit

class BorsaApp:Application(){
    lateinit var db:AppDatabase

    override fun onCreate(){
        super.onCreate()
        db=Room.databaseBuilder(this,AppDatabase::class.java,"borsa.db")
            .addMigrations(MIGRATION_1_2)
            .build()
        Notifications.createChannel(this)
        scheduleBackgroundWork()
    }

    private fun scheduleBackgroundWork(){
        val net=HistoricalWorker.networkConstraint()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_incremental_sync_kickoff",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncKickoffWorker>(12,TimeUnit.HOURS)
                .setConstraints(net).build()
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "historical_queue_analysis",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<QueueAnalysisWorker>(1,TimeUnit.HOURS)
                .setConstraints(net).build()
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "live_monitor",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LiveWorker>(15,TimeUnit.MINUTES)
                .setConstraints(net).build()
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "metadata_refresh",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MetadataWorker>(12,TimeUnit.HOURS)
                .setConstraints(net).build()
        )

        WorkManager.getInstance(this).enqueueUniqueWork(
            "metadata_refresh_now",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MetadataWorker>().setConstraints(net).build()
        )
    }

    companion object{
        val MIGRATION_1_2=object:Migration(1,2){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE symbols ADD COLUMN flow INTEGER")
                db.execSQL("ALTER TABLE symbols ADD COLUMN segment TEXT NOT NULL DEFAULT 'OTHER'")
                db.execSQL("ALTER TABLE symbols ADD COLUMN boardTitle TEXT")
            }
        }
    }
}
