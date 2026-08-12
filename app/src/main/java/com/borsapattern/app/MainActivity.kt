package com.borsapattern.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity:ComponentActivity(){
    private val notifPerm=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(android.os.Build.VERSION.SDK_INT>=33)notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent{AppUi()}
    }

    @Composable fun AppUi(){
        val app=application as BorsaApp
        var symbols by remember{mutableStateOf(0)}; var days by remember{mutableStateOf(0)}
        var candidates by remember{mutableStateOf(0)}; var confirmed by remember{mutableStateOf(0)}
        var latest by remember{mutableStateOf<Int?>(null)}; var scores by remember{mutableStateOf(emptyList<LiveScoreEntity>())}
        var history by remember{mutableStateOf(emptyList<QueueHistoryRow>())}; var tab by remember{mutableIntStateOf(0)}
        var status by remember{mutableStateOf("رصد پس‌زمینه فعال")}
        LaunchedEffect(Unit){while(true){
            symbols=app.db.dao().symbolCount();days=app.db.dao().dailyCount();candidates=app.db.dao().eventCount();confirmed=app.db.dao().confirmedCount();latest=app.db.dao().latestMarketDate();scores=app.db.dao().topScores();history=app.db.dao().confirmedHistory();delay(2500)
        }}
        MaterialTheme{Surface(Modifier.fillMaxSize()){Column(Modifier.padding(14.dp)){
            Text("Borsa Pattern",style=MaterialTheme.typography.headlineMedium);Text("کشف الگوی قبل از صف خرید و بک‌تست یک‌ساله")
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                Text("داده ذخیره‌شده روی گوشی")
                Text("نمادها: $symbols   |   رکوردها: $days")
                Text("کاندیدها: $candidates   |   صف تأییدشده: $confirmed")
                Text("آخرین روز بازار: ${latest?:"—"}")
                Text("وضعیت: $status")
            }}
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={
                    val net=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    WorkManager.getInstance(this@MainActivity).enqueueUniqueWork("manual_sync",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<HistoricalWorker>().setConstraints(net).build());status="فقط داده‌های جدید در حال همگام‌سازی"
                },modifier=Modifier.weight(1f)){Text("به‌روزرسانی داده")}
                Button(onClick={
                    val net=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    WorkManager.getInstance(this@MainActivity).enqueue(OneTimeWorkRequestBuilder<QueueAnalysisWorker>().setConstraints(net).build());status="تحلیل صف‌های تاریخی در حال اجرا"
                },modifier=Modifier.weight(1f)){Text("استخراج الگو")}
            }
            TabRow(selectedTabIndex=tab){Tab(tab==0,{tab=0},text={Text("فرصت‌های فعلی")});Tab(tab==1,{tab=1},text={Text("تاریخچه صف‌ها")})}
            if(tab==0) LazyColumn{items(scores){s->ListItem(headlineContent={Text(s.symbol?:s.insCode)},supportingContent={Text(s.reason)},trailingContent={Text(s.score.toInt().toString())});HorizontalDivider()}}
            else LazyColumn{items(history){h->
                ListItem(headlineContent={Text("${h.symbol?:h.insCode} — ${h.date}")},supportingContent={Text("زمان صف: ${fmtTime(h.eventTime)}  |  ارزش صف: ${fmtMoney(h.queueValue)}")},trailingContent={Text(h.score.toInt().toString())});HorizontalDivider()
            }}
        }}}
    }
    private fun fmtTime(v:Int?):String{if(v==null)return "—";val s=v.toString().padStart(6,'0');return "${s.substring(0,2)}:${s.substring(2,4)}"}
    private fun fmtMoney(v:Double?):String{if(v==null||v<=0)return "—";return when{v>=10_000_000_000.0->String.format(Locale.US,"%.1f میلیارد تومان",v/10_000_000_000.0);else->String.format(Locale.US,"%.0f میلیون تومان",v/10_000_000.0)}}
}
