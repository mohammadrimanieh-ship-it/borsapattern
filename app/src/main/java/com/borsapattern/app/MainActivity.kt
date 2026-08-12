package com.borsapattern.app

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import kotlinx.coroutines.delay
import java.util.*

class MainActivity:ComponentActivity(){
    private val notifPerm=registerForActivityResult(ActivityResultContracts.RequestPermission()){}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(android.os.Build.VERSION.SDK_INT>=33)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent{ BorsaTheme{ AppUi() } }
    }

    @Composable
    private fun BorsaTheme(content: @Composable () -> Unit){
        MaterialTheme(
            colorScheme=lightColorScheme(
                primary=Color(0xFF6546B8),
                secondary=Color(0xFF8B6BD8),
                surface=Color(0xFFFFF9FF),
                background=Color(0xFFFFF9FF),
                primaryContainer=Color(0xFFE9DEFF)
            ),
            content=content
        )
    }

    @Composable
    fun AppUi(){
        val app=application as BorsaApp
        val syncPrefs=remember{getSharedPreferences("sync",Context.MODE_PRIVATE)}
        val analysisPrefs=remember{getSharedPreferences("analysis",Context.MODE_PRIVATE)}
        val metaPrefs=remember{getSharedPreferences("metadata",Context.MODE_PRIVATE)}

        var symbols by remember{mutableStateOf(0)}
        var days by remember{mutableStateOf(0)}
        var candidates by remember{mutableStateOf(0)}
        var confirmed by remember{mutableStateOf(0)}
        var rejected by remember{mutableStateOf(0)}
        var errors by remember{mutableStateOf(0)}
        var latest by remember{mutableStateOf<Int?>(null)}
        var scores by remember{mutableStateOf(emptyList<LiveScoreEntity>())}
        var history by remember{mutableStateOf(emptyList<QueueHistoryRow>())}
        var tab by remember{mutableIntStateOf(0)}
        var showMarkets by remember{mutableStateOf(false)}
        var syncStatus by remember{mutableStateOf("آماده")}
        var syncDone by remember{mutableStateOf(0)}
        var syncTotal by remember{mutableStateOf(0)}
        var analysisStatus by remember{mutableStateOf("آماده")}
        var analysisDone by remember{mutableStateOf(0)}
        var analysisTotal by remember{mutableStateOf(0)}
        var metadataStatus by remember{mutableStateOf("آماده")}

        LaunchedEffect(Unit){
            while(true){
                val segs=MarketPrefs.selected(this@MainActivity).toList()
                symbols=app.db.dao().symbolCount()
                days=app.db.dao().dailyCount()
                candidates=app.db.dao().candidateCount()
                confirmed=app.db.dao().confirmedCount()
                rejected=app.db.dao().rejectedCount()
                errors=app.db.dao().errorCount()
                latest=app.db.dao().latestMarketDate()
                scores=app.db.dao().topScoresFor(segs)
                history=app.db.dao().confirmedHistoryFor(segs)
                syncStatus=syncPrefs.getString("sync_status","آماده")?:"آماده"
                syncDone=syncPrefs.getInt("sync_done",0)
                syncTotal=syncPrefs.getInt("sync_total",0)
                analysisStatus=analysisPrefs.getString("analysis_status","آماده")?:"آماده"
                analysisDone=analysisPrefs.getInt("analysis_batch_done",0)
                analysisTotal=analysisPrefs.getInt("analysis_batch_total",0)
                metadataStatus=metaPrefs.getString("status","آماده")?:"آماده"
                delay(1200)
            }
        }

        Scaffold(
            containerColor=MaterialTheme.colorScheme.background,
            topBar={
                Surface(shadowElevation=3.dp){
                    Column(Modifier.fillMaxWidth().padding(18.dp,14.dp)){
                        Text("Borsa Pattern",fontSize=30.sp,fontWeight=FontWeight.Black)
                        Text("الگو + تکنیکال + حجم",color=MaterialTheme.colorScheme.primary)
                    }
                }
            }
        ){pad->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal=14.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)
            ){
                item{
                    Spacer(Modifier.height(4.dp))
                    DashboardCard(days,symbols,candidates,confirmed,rejected,errors,latest)
                }

                item{
                    ProcessCard(
                        title="دریافت تاریخچه",
                        status=syncStatus,
                        done=syncDone,total=syncTotal
                    )
                }
                item{
                    ProcessCard(
                        title="تحلیل الگو",
                        status=analysisStatus,
                        done=analysisDone,total=analysisTotal
                    )
                }
                item{
                    Card(shape=RoundedCornerShape(18.dp)){
                        Column(Modifier.padding(14.dp)){
                            Text("نام نمادها",fontWeight=FontWeight.Bold)
                            Text(metadataStatus)
                        }
                    }
                }

                item{
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilledTonalButton(onClick={showMarkets=true},modifier=Modifier.weight(1f)){
                            Text("انتخاب بازارها")
                        }
                        Button(
                            onClick={
                                HistoricalWorker.start(this@MainActivity,false)
                                WorkManager.getInstance(this@MainActivity).enqueueUniqueWork(
                                    MetadataWorker.CHAIN,ExistingWorkPolicy.REPLACE,
                                    OneTimeWorkRequestBuilder<MetadataWorker>()
                                        .setConstraints(HistoricalWorker.networkConstraint())
                                        .setInputData(workDataOf("batch" to 35)).build()
                                )
                            },modifier=Modifier.weight(1f)
                        ){Text("به‌روزرسانی")}
                    }
                }

                item{
                    Button(
                        onClick={
                            val req=OneTimeWorkRequestBuilder<QueueAnalysisWorker>()
                                .setConstraints(HistoricalWorker.networkConstraint())
                                .setInputData(workDataOf("batchSize" to 240,"parallelism" to 4))
                                .build()
                            WorkManager.getInstance(this@MainActivity).enqueueUniqueWork(
                                QueueAnalysisWorker.ANALYSIS_CHAIN,ExistingWorkPolicy.REPLACE,req
                            )
                        },
                        modifier=Modifier.fillMaxWidth()
                    ){Text("تحلیل سریع الگو")}
                }

                item{
                    TabRow(selectedTabIndex=tab,containerColor=Color.Transparent){
                        Tab(tab==0,{tab=0},text={Text("فرصت‌های فعلی")})
                        Tab(tab==1,{tab=1},text={Text("تاریخچه صف‌ها")})
                    }
                }

                if(tab==0){
                    if(scores.isEmpty()) item{EmptyCard("فعلاً فرصت زنده‌ای ثبت نشده")}
                    else items(scores){s->SignalCard(s)}
                }else{
                    if(history.isEmpty()) item{EmptyCard("صف تأییدشده‌ای برای بازارهای انتخابی ثبت نشده")}
                    else items(history){h->HistoryCard(h)}
                }
                item{Spacer(Modifier.height(30.dp))}
            }
        }

        if(showMarkets){
            MarketDialog(
                initial=MarketPrefs.selected(this),
                onDismiss={showMarkets=false},
                onSave={MarketPrefs.save(this,it);showMarkets=false}
            )
        }
    }

    @Composable
    private fun DashboardCard(
        days:Int,symbols:Int,candidates:Int,confirmed:Int,rejected:Int,errors:Int,latest:Int?
    ){
        Card(
            shape=RoundedCornerShape(24.dp),
            colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)
        ){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("داشبورد",fontWeight=FontWeight.Bold,fontSize=18.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    MiniStat("رکورد",days,Modifier.weight(1f))
                    MiniStat("نماد",symbols,Modifier.weight(1f))
                    MiniStat("صف",confirmed,Modifier.weight(1f))
                }
                Text("کاندید باقی‌مانده: ${fa(candidates)}   •   ردشده: ${fa(rejected)}")
                Text("خطای دریافت: ${fa(errors)}")
                Text("آخرین روز بازار: ${Jalali.fromGregorianInt(latest)}")
            }
        }
    }

    @Composable
    private fun ProcessCard(title:String,status:String,done:Int,total:Int){
        Card(shape=RoundedCornerShape(18.dp)){
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(title,fontWeight=FontWeight.Bold)
                Text(status)
                if(total>0 && done<total){
                    LinearProgressIndicator(
                        progress={done.toFloat()/total.toFloat()},
                        modifier=Modifier.fillMaxWidth()
                    )
                    Text("${fa(done)} از ${fa(total)}")
                }
            }
        }
    }

    @Composable
    private fun MiniStat(title:String,value:Int,modifier:Modifier){
        Surface(modifier,shape=RoundedCornerShape(16.dp),color=Color.White.copy(alpha=.75f)){
            Column(Modifier.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Text(fa(value),fontWeight=FontWeight.Black,fontSize=20.sp)
                Text(title,fontSize=12.sp)
            }
        }
    }

    @Composable
    private fun SignalCard(s:LiveScoreEntity){
        val total=s.score.toInt()
        Card(shape=RoundedCornerShape(20.dp)){
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Text(s.symbol?:"در حال تکمیل نام",fontWeight=FontWeight.Bold,fontSize=19.sp)
                    Surface(
                        shape=RoundedCornerShape(16.dp),
                        color=MaterialTheme.colorScheme.primaryContainer
                    ){
                        Text("${fa(total)}/۱۰۰",Modifier.padding(10.dp),fontWeight=FontWeight.Black)
                    }
                }
                Text(s.reason,color=Color.DarkGray)
                ScoreBar("الگوی صف",s.patternScore)
                ScoreBar("تکنیکال",s.technicalScore)
                ScoreBar("حجم",s.volumeScore)
                val rsi=s.rsi
                if(rsi!=null) Text("RSI: ${Jalali.digits(String.format(Locale.US,"%.1f",rsi))}")
            }
        }
    }

    @Composable
    private fun ScoreBar(label:String,value:Double){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text(label,Modifier.width(75.dp),fontSize=12.sp)
            LinearProgressIndicator(
                progress={(value/100.0).toFloat().coerceIn(0f,1f)},
                modifier=Modifier.weight(1f)
            )
            Text(fa(value.toInt()),Modifier.width(38.dp),fontSize=12.sp)
        }
    }

    @Composable
    private fun HistoryCard(h:QueueHistoryRow){
        Card(shape=RoundedCornerShape(20.dp)){
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Text(h.symbol?:"در حال تکمیل نام",fontWeight=FontWeight.Bold,fontSize=19.sp)
                    Text("${fa(h.score.toInt())}/۱۰۰",fontWeight=FontWeight.Black)
                }
                Text(Jalali.fromGregorianInt(h.date),color=MaterialTheme.colorScheme.primary)
                Text("زمان صف: ${fmtTime(h.eventTime)}")
                Text("ارزش صف: ${fmtMoney(h.queueValue)}")
            }
        }
    }

    @Composable
    private fun EmptyCard(text:String){
        Card(shape=RoundedCornerShape(20.dp)){
            Text(text,Modifier.fillMaxWidth().padding(24.dp),color=Color.Gray)
        }
    }

    @Composable
    private fun MarketDialog(initial:Set<String>,onDismiss:()->Unit,onSave:(Set<String>)->Unit){
        var selected by remember{mutableStateOf(initial)}
        AlertDialog(
            onDismissRequest=onDismiss,
            title={Text("بازارهای مورد بررسی")},
            text={
                Column{
                    MarketPrefs.all.forEach{s->
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Checkbox(
                                checked=selected.contains(s),
                                onCheckedChange={on->selected=if(on) selected+s else selected-s}
                            )
                            Text(MarketPrefs.label(s))
                        }
                    }
                }
            },
            confirmButton={
                TextButton(onClick={if(selected.isNotEmpty()) onSave(selected) else onDismiss()}){
                    Text("ذخیره")
                }
            },
            dismissButton={TextButton(onClick=onDismiss){Text("انصراف")}}
        )
    }

    private fun fa(v:Int)=Jalali.digits(v.toString())

    private fun fmtTime(v:Int?):String{
        if(v==null)return "—"
        val s=v.toString().padStart(6,'0')
        return Jalali.digits("${s.substring(0,2)}:${s.substring(2,4)}")
    }

    private fun fmtMoney(v:Double?):String{
        if(v==null||v<=0)return "—"
        val raw=when{
            v>=10_000_000_000.0 -> String.format(Locale.US,"%.1f میلیارد تومان",v/10_000_000_000.0)
            else -> String.format(Locale.US,"%.0f میلیون تومان",v/10_000_000.0)
        }
        return Jalali.digits(raw)
    }
}
