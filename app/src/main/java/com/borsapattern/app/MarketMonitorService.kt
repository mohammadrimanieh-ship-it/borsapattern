package com.borsapattern.app

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class MarketMonitorService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)

    override fun onCreate(){
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putBoolean("background_enabled",true)
            .putBoolean("service_running",true)
            .apply()

        startForeground(NOTIFICATION_ID,notification("پایش بازار فعال"))
        scope.coroutineContext.cancelChildren()
        scope.launch{monitorLoop()}
        return START_STICKY
    }

    private suspend fun monitorLoop(){
        while(isActive){
            val prefs=getSharedPreferences(PREFS,MODE_PRIVATE)
            if(!prefs.getBoolean("background_enabled",true)){
                stopSelf()
                break
            }

            val phase=MarketClock.phase()
            if(MarketClock.isLiveWindow()){
                try{
                    val scanned=LiveScanEngine.scanOnce(this@MarketMonitorService)
                    val live=getSharedPreferences("live_monitor",MODE_PRIVATE)
                    val count=live.getInt("live_count",0)
                    val status=live.getString("market_status","بازار باز")?:"بازار باز"
                    updateNotification(
                        if(status=="بازار باز")
                            "بازار باز • پایش تطبیقی • ${Jalali.toFa(count)} نماد فعال"
                        else status
                    )
                    if(scanned==0 && status!="بازار باز"){
                        delay(120_000L)
                    }else{
                        delay(LiveScanEngine.recommendedDelayMillis(this@MarketMonitorService))
                    }
                }catch(e:Exception){
                    updateNotification("پایش بازار • تلاش مجدد پس از خطای شبکه")
                    delay(30_000L)
                }
            }else if(phase.contains("پیش")){
                updateNotification(
                    "${MarketClock.currentJalaliDate()} • پیش‌گشایش • انتظار تا ۰۹:۰۰"
                )
                delay(30_000L)
            }else{
                // Keep the preference enabled, but stop the foreground service
                // outside the trading window. WorkManager will wake it again
                // during market hours. This avoids Android's long-running FGS limits.
                stopSelf()
                break
            }
        }
    }

    private fun createChannel(){
        val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "پایش پس‌زمینه Signal",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun notification(text:String):Notification{
        val launch=Intent(this,MainActivity::class.java)
        val pi=PendingIntent.getActivity(
            this,0,launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Signal — پایش بازار")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text:String){
        val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID,notification(text))
    }

    override fun onDestroy(){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putBoolean("service_running",false)
            .apply()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent:Intent?):android.os.IBinder?=null

    companion object{
        const val PREFS="market_monitor"
        private const val CHANNEL="market_monitor_service"
        private const val NOTIFICATION_ID=2901

        fun start(context:Context){
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .edit().putBoolean("background_enabled",true).apply()
            val i=Intent(context,MarketMonitorService::class.java)
            if(Build.VERSION.SDK_INT>=26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context:Context){
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .edit()
                .putBoolean("background_enabled",false)
                .putBoolean("service_running",false)
                .apply()
            context.stopService(Intent(context,MarketMonitorService::class.java))
        }
    }
}
