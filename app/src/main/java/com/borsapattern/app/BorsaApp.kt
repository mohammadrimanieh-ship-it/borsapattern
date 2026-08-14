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
            .addMigrations(
                MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,
                MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9,MIGRATION_9_10
            )
            .build()
        Notifications.createChannel(this)

        // v2.9.3 rescue: no WorkManager jobs are started from Application.onCreate.
        // This guarantees that UI startup is not blocked by a worker opening/migrating Room.
        // User-triggered jobs and the foreground live monitor still work after the Activity opens.
    }

    private fun scheduleBackgroundWork(){
        val wm=WorkManager.getInstance(this)

        // Extraction and historical analysis are user-controlled only.
        // Cancel legacy periodic jobs left by older versions.
        wm.cancelUniqueWork("daily_incremental_sync_kickoff")
        wm.cancelUniqueWork("historical_queue_analysis")

        // Keep only lightweight live monitoring; it respects the 09:00-12:30 window.
        val net=HistoricalWorker.networkConstraint()
        wm.enqueueUniquePeriodicWork(
            "live_monitor",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LiveWorker>(15,TimeUnit.MINUTES)
                .setConstraints(net)
                .build()
        )

        // Local-only classification repair; no network extraction.
        wm.enqueueUniqueWork(
            "category_repair",
            ExistingWorkPolicy.KEEP,
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
        val MIGRATION_6_7=object:Migration(6,7){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS prequeue_snapshots (
                        insCode TEXT NOT NULL,
                        date INTEGER NOT NULL,
                        minutesBefore INTEGER NOT NULL,
                        snapshotTime INTEGER NOT NULL,
                        score REAL NOT NULL,
                        bidImbalance REAL NOT NULL,
                        bidGrowth REAL NOT NULL,
                        askDrop REAL NOT NULL,
                        pricePressure REAL NOT NULL,
                        label INTEGER NOT NULL,
                        detected INTEGER NOT NULL,
                        PRIMARY KEY(insCode,date,minutesBefore)
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_7_8=object:Migration(7,8){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE queue_events ADD COLUMN queueDurationMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN queuePersistenceRatio REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN queueBreakCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN queueEndHeld INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN queueValueRetention REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_events ADD COLUMN nextDayReturnPct REAL")
            }
        }
        val MIGRATION_8_9=object:Migration(8,9){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE live_scores ADD COLUMN firstAlertAt INTEGER")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN alertLevel TEXT NOT NULL DEFAULT 'WATCH'")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN sessionDate INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN queueDetectedAt INTEGER")
                db.execSQL("ALTER TABLE live_scores ADD COLUMN leadSeconds INTEGER")
            }
        }

        val MIGRATION_9_10=object:Migration(9,10){
            override fun migrate(db:SupportSQLiteDatabase){
                // Rebuild only the lightweight live table. Historical data,
                // queue_events, daily and prequeue_snapshots are preserved.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS live_scores_new (
                        insCode TEXT NOT NULL,
                        symbol TEXT,
                        score REAL NOT NULL,
                        reason TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        patternScore REAL NOT NULL,
                        technicalScore REAL NOT NULL,
                        volumeScore REAL NOT NULL,
                        rsi REAL,
                        macd REAL,
                        actorScore REAL NOT NULL,
                        lastPrice REAL NOT NULL,
                        firstAlertAt INTEGER,
                        alertLevel TEXT NOT NULL DEFAULT 'WATCH',
                        sessionDate INTEGER NOT NULL DEFAULT 0,
                        queueDetectedAt INTEGER,
                        leadSeconds INTEGER,
                        PRIMARY KEY(insCode)
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT OR REPLACE INTO live_scores_new(
                        insCode,symbol,score,reason,updatedAt,
                        patternScore,technicalScore,volumeScore,rsi,macd,
                        actorScore,lastPrice,firstAlertAt,alertLevel,sessionDate,
                        queueDetectedAt,leadSeconds
                    )
                    SELECT
                        insCode,symbol,score,reason,updatedAt,
                        COALESCE(patternScore,0),COALESCE(technicalScore,0),
                        COALESCE(volumeScore,0),rsi,macd,
                        COALESCE(actorScore,0),COALESCE(lastPrice,0),
                        firstAlertAt,COALESCE(alertLevel,'WATCH'),
                        COALESCE(sessionDate,0),queueDetectedAt,leadSeconds
                    FROM live_scores
                """.trimIndent())

                db.execSQL("DROP TABLE live_scores")
                db.execSQL("ALTER TABLE live_scores_new RENAME TO live_scores")
            }
        }
    }
}
