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
            .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6)
            .build()
        Notifications.createChannel(this)
        scheduleBackgroundWork()
    }

    private fun scheduleBackgroundWork(){
        val net=HistoricalWorker.networkConstraint()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_incremental_sync_kickoff",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncKickoffWorker>(12,TimeUnit.HOURS)
                .setConstraints(net).build()
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "historical_queue_analysis",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<QueueAnalysisWorker>(1,TimeUnit.HOURS)
                .setConstraints(net).build()
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "live_monitor",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LiveWorker>(15,TimeUnit.MINUTES)
                .setConstraints(net).build()
        )

        WorkManager.getInstance(this).enqueueUniqueWork(
            "category_repair",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CategoryRepairWorker>().build()
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

        val MIGRATION_2_3=object:Migration(2,3){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE live_scores ADD COLUMN patternScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN technicalScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN volumeScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN rsi REAL")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN macd REAL")
            }
        }

        val MIGRATION_3_4=object:Migration(3,4){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE live_scores ADD COLUMN actorScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN lastPrice REAL NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS paper_trades (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        insCode TEXT NOT NULL,
                        symbol TEXT,
                        entryPrice REAL NOT NULL,
                        currentPrice REAL NOT NULL,
                        entryTime INTEGER NOT NULL,
                        exitTime INTEGER,
                        exitPrice REAL,
                        status TEXT NOT NULL,
                        entryScore REAL NOT NULL,
                        pnlPct REAL NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5=object:Migration(4,5){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL(
                    "ALTER TABLE symbols ADD COLUMN instrumentType TEXT NOT NULL DEFAULT 'TYPE_STOCK'"
                )
            }
        }

        val MIGRATION_5_6=object:Migration(5,6){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE queue_events ADD COLUMN signalTime INTEGER")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN nextTradingDate INTEGER")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN nextDayQueueStatus TEXT NOT NULL DEFAULT 'PENDING'")
            }
        }
    }
}
