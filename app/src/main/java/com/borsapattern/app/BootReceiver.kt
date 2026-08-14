package com.borsapattern.app

import android.content.*

class BootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED &&
           intent.action!="android.intent.action.MY_PACKAGE_REPLACED") return

        val enabled=context.getSharedPreferences(
            MarketMonitorService.PREFS,Context.MODE_PRIVATE
        ).getBoolean("background_enabled",false)

        if(enabled && (MarketClock.isLiveWindow() || MarketClock.phase().contains("پیش"))){
            runCatching{MarketMonitorService.start(context)}
        }
    }
}
