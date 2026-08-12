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
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity: ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppUi() }
    }

    @Composable
    fun AppUi() {
        val app = application as BorsaApp
        var symbols by remember { mutableStateOf(0) }
        var days by remember { mutableStateOf(0) }
        var events by remember { mutableStateOf(0) }
        var scores by remember { mutableStateOf(emptyList<LiveScoreEntity>()) }
        var status by remember { mutableStateOf("آماده") }

        LaunchedEffect(Unit) {
            while(true) {
                symbols = app.db.dao().symbolCount()
                days = app.db.dao().dailyCount()
                events = app.db.dao().eventCount()
                scores = app.db.dao().topScores()
                delay(2000)
            }
        }

        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Borsa Pattern", style=MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("تشخیص رفتار شبیه قبل از صف خرید")
                    Spacer(Modifier.height(16.dp))

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("وضعیت داده")
                            Text("نمادها: $symbols")
                            Text("رکورد روزانه: $days")
                            Text("رویدادهای کاندید: $events")
                            Text("وضعیت: $status")
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(onClick={
                        val r=OneTimeWorkRequestBuilder<HistoricalWorker>()
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .build()
                        WorkManager.getInstance(this@MainActivity).enqueueUniqueWork(
                            "historical", ExistingWorkPolicy.KEEP, r
                        )
                        status="دانلود تاریخچه شروع شد"
                    }, modifier=Modifier.fillMaxWidth()) { Text("دریافت و تحلیل تاریخچه یک‌سال") }

                    Button(onClick={
                        val req=PeriodicWorkRequestBuilder<LiveWorker>(15, TimeUnit.MINUTES)
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .build()
                        WorkManager.getInstance(this@MainActivity).enqueueUniquePeriodicWork(
                            "live_monitor", ExistingPeriodicWorkPolicy.UPDATE, req
                        )
                        status="رصد پس‌زمینه فعال شد"
                    }, modifier=Modifier.fillMaxWidth()) { Text("فعال‌کردن رصد پس‌زمینه") }

                    Button(onClick={
                        lifecycleScope.launch {
                            LiveWorker::class // فقط برای جلوگیری از حذف کلاس
                            val r=OneTimeWorkRequestBuilder<LiveWorker>().build()
                            WorkManager.getInstance(this@MainActivity).enqueue(r)
                            status="اسکن زنده اجرا شد"
                        }
                    }, modifier=Modifier.fillMaxWidth()) { Text("اسکن زنده همین حالا") }

                    Spacer(Modifier.height(16.dp))
                    Text("فرصت‌های فعلی", style=MaterialTheme.typography.titleMedium)
                    LazyColumn {
                        items(scores) { s ->
                            ListItem(
                                headlineContent={ Text(s.symbol ?: s.insCode) },
                                supportingContent={ Text(s.reason) },
                                trailingContent={ Text("${s.score.toInt()}") }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
