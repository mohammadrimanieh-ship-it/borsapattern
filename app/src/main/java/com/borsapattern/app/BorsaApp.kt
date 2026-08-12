package com.borsapattern.app

import android.app.Application
import androidx.room.Room

class BorsaApp : Application() {
    lateinit var db: AppDatabase
    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "borsa.db").build()
        Notifications.createChannel(this)
    }
}
