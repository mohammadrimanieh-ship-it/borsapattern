package com.borsapattern.app

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity:ComponentActivity(){
    private val notifPerm=registerForActivityResult(ActivityResultContracts.RequestPermission()){}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(android.os.Build.VERSION.SDK_INT>=33)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { AppUi() } }
    }

    @Composable
    private fun AppTheme(content: @Composable () -> Unit){
        MaterialTheme(
            colorScheme=lightColorScheme(
                primary=Color(0xFF5B3FB2),
                secondary=Color(0xFF8B6BDD),
                background=Color(0xFFF8F4FF),
                surface=Color.White,
                primaryContainer=Color(0xFFE9E0FF),
                secondaryContainer=Color(0xFFF0EAFF)
            ),
            content=content
        )
    }

    @Composable
    private fun AppUi(){
        val app=application as BorsaApp
        val syncPrefs=remember{getSharedPreferences("sync",Context.MODE_PRIVATE)}
        val analysisPrefs=remember{getSharedPreferences("analysis",Context.MODE_PRIVATE)}
        val metaPrefs=remember{getSharedPreferences("metadata",Context.MODE_PRIVATE)}
        val nextPrefs=remember{getSharedPreferences("nextday",Context.MODE_PRIVATE)}

        var symbols by remember{mutableStateOf(0)}
        var records by remember{mutableStateOf(0)}
        var candidates by remember{mutableStateOf(0)}
        var confirmed by remember{mutableStateOf(0)}
        var rejected by remember{mutableStateOf(0)}
        var errors by remember{mutableStateOf(0)}
        var latest by remember{mutableStateOf<Int?>(null)}
        var scores by remember{mutableStateOf(emptyList<LiveScoreEntity>())}
        var history by remember{mutableStateOf(emptyList<QueueHistoryRow>())}
        var trades by remember{mutableStateOf(emptyList<PaperTradeEntity>())}

        var section by remember{mutableIntStateOf(0)}
        var searchText by remember{mutableStateOf("")}
        var searchResults by remember{mutableStateOf(emptyList<SymbolEntity>())}
        var selectedSymbol by remember{mutableStateOf<SymbolEntity?>(null)}
        var selectedSignals by remember{mutableStateOf(emptyList<SymbolSignalRow>())}
        var liveEnabled by remember{mutableStateOf(false)}
        var lastLiveScan by remember{mutableStateOf<Long?>(null)}
        var showMarkets by remember{mutableStateOf(false)}

        var syncStatus by remember{mutableStateOf("آماده")}
        var syncDone by remember{mutableStateOf(0)}
        var syncTotal by remember{mutableStateOf(0)}
        var analysisStatus by remember{mutableStateOf("آماده")}
        var analysisDone by remember{mutableStateOf(0)}
        var analysisTotal by remember{mutableStateOf(0)}
        var metadataStatus by remember{mutableStateOf("آماده")}
        var nextDayStatus by remember{mutableStateOf("آماده")}

        LaunchedEffect(Unit){
            while(true){
                val segs=MarketPrefs.selectedSegments(this@MainActivity).toList()
                val types=MarketPrefs.selectedTypes(this@MainActivity).toList()
                symbols=app.db.dao().symbolCount()
                records=app.db.dao().dailyCount()
                candidates=app.db.dao().candidateCount()
                confirmed=app.db.dao().confirmedCount()
                rejected=app.db.dao().rejectedCount()
                errors=app.db.dao().errorCount()
                latest=app.db.dao().latestMarketDate()
                scores=app.db.dao().topScoresFor(segs,types)
                history=app.db.dao().confirmedHistoryFor(segs,types)
                trades=app.db.dao().recentPaperTrades(100)

                syncStatus=syncPrefs.getString("sync_status","آماده")?:"آماده"
                syncDone=syncPrefs.getInt("sync_done",0)
                syncTotal=syncPrefs.getInt("sync_total",0)
                analysisStatus=analysisPrefs.getString("analysis_status","آماده")?:"آماده"
                analysisDone=analysisPrefs.getInt("analysis_batch_done",0)
                analysisTotal=analysisPrefs.getInt("analysis_batch_total",0)
                metadataStatus=metaPrefs.getString("status","آماده")?:"آماده"
                nextDayStatus=nextPrefs.getString("status","آماده")?:"آماده"
                delay(1200)
            }
        }

        LaunchedEffect(searchText){
            if(searchText.trim().isNotEmpty()){
                delay(250); searchResults=app.db.dao().searchSymbols(searchText.trim(),30)
            }else searchResults=emptyList()
        }
        LaunchedEffect(selectedSymbol?.insCode){
            selectedSignals=selectedSymbol?.let{app.db.dao().signalHistoryForSymbol(it.insCode,250)} ?: emptyList()
        }
        BackHandler(enabled=true){
            when{
                selectedSymbol!=null -> selectedSymbol=null
                section!=0 -> section=0
                else -> finish()
            }
        }

        LaunchedEffect(liveEnabled){
            while(liveEnabled){
                try{
                    LiveScanEngine.scanOnce(this@MainActivity)
                    lastLiveScan=System.currentTimeMillis()
                }catch(_:Exception){}
                delay(5000)
            }
        }

        Scaffold(
            containerColor=MaterialTheme.colorScheme.background,
            topBar={
                Surface(shadowElevation=4.dp){
                    Column(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp)){
                        Text("Borsa Pattern",fontSize=30.sp,fontWeight=FontWeight.Black)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.SpaceBetween,
                            verticalAlignment=Alignment.CenterVertically
                        ){
                            Text(
                                "رصد بازار • الگو • تکنیکال • Paper Trading",
                                color=MaterialTheme.colorScheme.primary,
                                fontSize=13.sp
                            )
                            Text(
                                "v1.3.1-test",
                                color=Color.Gray,
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold
                            )
                        }
                    }
                }
            }
        ){padding->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal=12.dp)
            ){
                ScrollableTabRow(
                    selectedTabIndex=section,
                    edgePadding=0.dp,
                    containerColor=Color.Transparent
                ){
                    Tab(section==0,{section=0},text={Text("سیگنال امروز")})
                    Tab(section==1,{section=1},text={Text("بک‌تست روزانه")})
                    Tab(section==2,{section=2},text={Text("جستجو 🔎")})
                    Tab(section==3,{section=3},text={Text("استخراج داده")})
                    Tab(section==4,{section=4},text={Text("آزمایشی")})
                }

                when(section){
                    0 -> DailySignals(scores,liveEnabled,{liveEnabled=it},lastLiveScan)
                    1 -> DailyBacktest(history)
                    2 -> SymbolSearchPage(
                        searchText,{searchText=it},searchResults,
                        selectedSymbol,selectedSignals,{selectedSymbol=it}
                    )
                    3 -> DataExtractionPage(
                        syncStatus,syncDone,syncTotal,metadataStatus,
                        onMarkets={showMarkets=true},
                        onNames={startNameRepair()},
                        onStart={symbolsText,years->
                            saveExtractionSelection(symbolsText,years)
                            startUpdate()
                        },
                        onAnalyze={startAnalyze()},
                        onNextDay={startNextDayCheck()}
                    )
                    else -> PaperTrades(trades)
                }
            }
        }

        if(showMarkets){
            MarketDialog(
                initialTypes=MarketPrefs.selectedTypes(this),
                initialSegments=MarketPrefs.selectedSegments(this),
                onDismiss={showMarkets=false},
                onSave={types,segments->
                    MarketPrefs.saveFilters(this,types,segments)
                    showMarkets=false
                }
            )
        }
    }


    @Composable
    private fun DailySignals(
        scores:List<LiveScoreEntity>,
        liveEnabled:Boolean,
        onLiveToggle:(Boolean)->Unit,
        lastLiveScan:Long?
    ){
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            item{
                Card(
                    shape=RoundedCornerShape(22.dp),
                    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)
                ){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("سیگنال‌های امروز",fontSize=22.sp,fontWeight=FontWeight.Black)
                        Text("فقط خروجی نهایی موتور سیگنال نمایش داده می‌شود.",fontSize=12.sp)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.SpaceBetween,
                            verticalAlignment=Alignment.CenterVertically
                        ){
                            Text(if(liveEnabled)"رصد زنده فعال است" else "رصد زنده خاموش است")
                            Switch(checked=liveEnabled,onCheckedChange=onLiveToggle)
                        }
                        if(lastLiveScan!=null) Text("آخرین اسکن: ${clock(lastLiveScan)}",fontSize=11.sp)
                    }
                }
            }
            val strong=scores.filter{it.score>=60}.sortedByDescending{it.score}
            if(strong.isEmpty()){
                item{Empty("فعلاً سیگنال روزانه قابل نمایش نیست")}
            }else{
                items(strong){s->
                    Card(shape=RoundedCornerShape(18.dp)){
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement=Arrangement.SpaceBetween,
                            verticalAlignment=Alignment.CenterVertically
                        ){
                            Column(Modifier.weight(1f)){
                                Text(s.symbol?:"در حال تکمیل نام",fontSize=19.sp,fontWeight=FontWeight.Bold)
                                Text(
                                    when{
                                        s.score>=85 -> "سیگنال قوی"
                                        s.score>=70 -> "سیگنال"
                                        else -> "تحت نظر"
                                    },
                                    color=MaterialTheme.colorScheme.primary,
                                    fontWeight=FontWeight.Bold
                                )
                                Text("آخرین قیمت: ${Jalali.digits(String.format(Locale.US,"%.0f",s.lastPrice))}",fontSize=11.sp)
                            }
                            ScoreBadge(s.score)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DailyBacktest(history:List<QueueHistoryRow>){
        val grouped=history.groupBy{it.date}.toSortedMap(compareByDescending{it})
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            item{
                Text("اگر در هر روز طبق الگوریتم عمل می‌کردیم چه می‌شد؟",
                    fontSize=18.sp,fontWeight=FontWeight.Black)
            }
            grouped.forEach{(date,rows)->
                item{
                    Card(shape=RoundedCornerShape(20.dp)){
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                            Text(Jalali.fromGregorianInt(date),fontSize=18.sp,fontWeight=FontWeight.Black)
                            rows.forEach{s->
                                HorizontalDivider()
                                Text(s.symbol?:"در حال تکمیل نام",fontWeight=FontWeight.Bold)
                                Text("هشدار: ${fmtTime(s.signalTime)} • صف: ${fmtTime(s.eventTime)} • امتیاز ${fa(s.score.toInt())}")
                                Text(when(s.nextDayQueueStatus){
                                    "QUEUE_AGAIN" -> "روز معاملاتی بعد: صف خرید ماند ✅"
                                    "NOT_QUEUE_NEXT_DAY" -> "روز معاملاتی بعد: صف خرید نماند ❌"
                                    "NO_NEXT_DAY" -> "روز بعد: داده موجود نیست"
                                    else -> "روز بعد: هنوز بررسی نشده"
                                },fontSize=12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DataExtractionPage(
        syncStatus:String,syncDone:Int,syncTotal:Int,metadataStatus:String,
        onMarkets:()->Unit,onNames:()->Unit,
        onStart:(String,Int)->Unit,onAnalyze:()->Unit,onNextDay:()->Unit
    ){
        var symbolsText by remember{mutableStateOf("")}
        var years by remember{mutableIntStateOf(5)}
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            item{
                Card(shape=RoundedCornerShape(22.dp)){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text("استخراج داده",fontSize=22.sp,fontWeight=FontWeight.Black)
                        Text("قبل از شروع، بازارها یا نمادهای موردنظر را انتخاب کن. اختیار معامله به‌طور کامل حذف شده است.",fontSize=12.sp)
                        Button(onClick=onMarkets,modifier=Modifier.fillMaxWidth()){Text("انتخاب بازار و نوع اوراق")}
                        OutlinedTextField(
                            value=symbolsText,onValueChange={symbolsText=it},
                            modifier=Modifier.fillMaxWidth(),
                            label={Text("نمادهای خاص (اختیاری)")},
                            supportingText={Text("مثال: وبملت، خودرو، شستا — خالی = همه نمادهای انتخاب‌شده")}
                        )
                        Text("بازه تاریخی")
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            (1..5).forEach{y->
                                FilterChip(
                                    selected=years==y,onClick={years=y},
                                    label={Text("$y سال")}
                                )
                            }
                        }
                        Button(onClick={onStart(symbolsText,years)},modifier=Modifier.fillMaxWidth()){
                            Text("شروع استخراج")
                        }
                    }
                }
            }
            item{ProcessCard("دریافت تاریخچه",syncStatus,syncDone,syncTotal)}
            item{
                Card(shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text("نام نمادها",fontWeight=FontWeight.Bold)
                        Text(metadataStatus,fontSize=12.sp)
                        FilledTonalButton(onClick=onNames,modifier=Modifier.fillMaxWidth()){
                            Text("ترمیم فقط نام‌های ناقص")
                        }
                    }
                }
            }
            item{FilledTonalButton(onClick=onAnalyze,modifier=Modifier.fillMaxWidth()){Text("تحلیل دیتاست تاریخی")}}
            item{FilledTonalButton(onClick=onNextDay,modifier=Modifier.fillMaxWidth()){Text("بررسی ماندگاری صف روز بعد")}}
            item{
                Text(
                    "هدف این نسخه: تاریخچه تا ۵ سال. داده‌هایی که منبع عمومی ارائه کند ذخیره می‌شوند؛ داده‌های غیرعمومی یا هویت اشخاص قابل استخراج نیست.",
                    fontSize=11.sp,color=Color.Gray
                )
            }
        }
    }

    @Composable
    private fun Dashboard(
        records:Int,symbols:Int,candidates:Int,confirmed:Int,rejected:Int,errors:Int,latest:Int?,
        syncStatus:String,syncDone:Int,syncTotal:Int,
        analysisStatus:String,analysisDone:Int,analysisTotal:Int,
        metadataStatus:String,liveEnabled:Boolean,lastLiveScan:Long?,
        onLiveToggle:(Boolean)->Unit,onUpdate:()->Unit,onAnalyze:()->Unit
    ){
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            item{
                Card(
                    shape=RoundedCornerShape(24.dp),
                    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)
                ){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Stat("رکورد",records,Modifier.weight(1f))
                            Stat("نماد",symbols,Modifier.weight(1f))
                            Stat("صف",confirmed,Modifier.weight(1f))
                        }
                        Text("کاندید: ${fa(candidates)}  •  ردشده: ${fa(rejected)}  •  خطا: ${fa(errors)}")
                        Text("آخرین روز بازار: ${Jalali.fromGregorianInt(latest)}")
                    }
                }
            }

            item{
                Card(shape=RoundedCornerShape(20.dp)){
                    Column(Modifier.padding(14.dp)){
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.SpaceBetween,
                            verticalAlignment=Alignment.CenterVertically
                        ){
                            Column{
                                Text("اسکن زنده ۵ ثانیه‌ای",fontWeight=FontWeight.Bold)
                                Text(
                                    if(liveEnabled) "فعال — کل MarketWatch در هر چرخه بررسی می‌شود"
                                    else "خاموش",
                                    fontSize=12.sp
                                )
                            }
                            Switch(checked=liveEnabled,onCheckedChange=onLiveToggle)
                        }
                        if(lastLiveScan!=null){
                            Text("آخرین اسکن: ${clock(lastLiveScan)}",fontSize=12.sp)
                        }
                    }
                }
            }

            item{ ProcessCard("دریافت تاریخچه",syncStatus,syncDone,syncTotal) }
            item{ ProcessCard("تحلیل الگو",analysisStatus,analysisDone,analysisTotal) }
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
                    Button(onClick=onUpdate,modifier=Modifier.weight(1f)){Text("به‌روزرسانی")}
                    Button(onClick=onAnalyze,modifier=Modifier.weight(1f)){Text("تحلیل سریع")}
                }
            }
        }
    }

    @Composable
    private fun Opportunities(scores:List<LiveScoreEntity>){
        if(scores.isEmpty()){
            Empty("فعلاً فرصت زنده‌ای ثبت نشده")
            return
        }
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            items(scores){s->
                Card(shape=RoundedCornerShape(22.dp)){
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Column{
                                Text(s.symbol?:"در حال تکمیل نام",fontWeight=FontWeight.Black,fontSize=20.sp)
                                Text(s.reason,fontSize=12.sp,color=Color.DarkGray)
                            }
                            ScoreBadge(s.score)
                        }

                        WeightedBar("الگوی صف",s.patternScore,40)
                        WeightedBar("تکنیکال",s.technicalScore,25)
                        WeightedBar("حجم",s.volumeScore,20)
                        WeightedBar("شباهت رفتاری*",s.actorScore,15)

                        if(s.rsi!=null){
                            Text("RSI: ${Jalali.digits(String.format(Locale.US,"%.1f",s.rsi))}",fontSize=12.sp)
                        }
                        Text("* شباهت رفتاری آزمایشی است و هویت یک معامله‌گر را اثبات نمی‌کند.",fontSize=10.sp,color=Color.Gray)
                    }
                }
            }
        }
    }

    @Composable
    private fun QueueHistory(items:List<QueueHistoryRow>){
        if(items.isEmpty()){
            Empty("هنوز صف تأییدشده‌ای برای بازارهای انتخابی ثبت نشده")
            return
        }
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(8.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            items(items){h->
                Card(shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(14.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Text(h.symbol?:"در حال تکمیل نام",fontWeight=FontWeight.Bold,fontSize=18.sp)
                            Text("${fa(h.score.toInt())}/۱۰۰",fontWeight=FontWeight.Black)
                        }
                        Text(Jalali.fromGregorianInt(h.date),color=MaterialTheme.colorScheme.primary)
                        Text("زمان صف: ${fmtTime(h.eventTime)}  •  ارزش صف: ${fmtMoney(h.queueValue)}")
                    }
                }
            }
        }
    }

    @Composable
    private fun PaperTrades(items:List<PaperTradeEntity>){
        if(items.isEmpty()){
            Empty("Paper Trading هنوز معامله‌ای ثبت نکرده؛ ورود فرضی از امتیاز ۸۲ به بالا انجام می‌شود.")
            return
        }
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(8.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            items(items){t->
                Card(shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Text(t.symbol?:"نماد",fontWeight=FontWeight.Bold,fontSize=18.sp)
                            Text(if(t.status=="OPEN") "باز" else "بسته",color=MaterialTheme.colorScheme.primary)
                        }
                        Text("ورود فرضی: ${faPrice(t.entryPrice)}")
                        Text("قیمت فعلی/خروج: ${faPrice(t.currentPrice)}")
                        Text("بازده فرضی: ${Jalali.digits(String.format(Locale.US,"%.2f%%",t.pnlPct))}")
                        Text("امتیاز ورود: ${fa(t.entryScore.toInt())}/۱۰۰")
                    }
                }
            }
        }
    }


    @Composable
    private fun SymbolSearchPage(
        query:String,onQuery:(String)->Unit,results:List<SymbolEntity>,
        selected:SymbolEntity?,signals:List<SymbolSignalRow>,onSelect:(SymbolEntity)->Unit
    ){
        if(selected!=null){
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=10.dp)){
                item{Card(shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp)){
                    Text(selected.symbol?:selected.name?:"نماد",fontSize=22.sp,fontWeight=FontWeight.Black)
                    Text("تاریخچه زمان‌های هشدار الگو",fontSize=12.sp)
                }}}
                items(signals){s->Card(shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(14.dp)){
                    Text(Jalali.fromGregorianInt(s.date),fontWeight=FontWeight.Bold)
                    Text("هشدار: ${fmtTime(s.signalTime)}  •  صف/رخداد: ${fmtTime(s.eventTime)}")
                    Text("امتیاز: ${fa(s.score.toInt())}/۱۰۰")
                    Text(when(s.nextDayQueueStatus){
                        "QUEUE_AGAIN"->"روز بعد: صف خرید ماند ✅"
                        "NOT_QUEUE_NEXT_DAY"->"روز بعد: صف نماند"
                        "NO_NEXT_DAY"->"روز بعد: داده موجود نیست"
                        else->"روز بعد: بررسی نشده"
                    },color=MaterialTheme.colorScheme.primary)
                }}}
            }
            return
        }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=10.dp)){
            item{OutlinedTextField(value=query,onValueChange=onQuery,modifier=Modifier.fillMaxWidth(),
                singleLine=true,label={Text("جستجوی نماد")})}
            items(results){s->
                Card(
                    modifier=Modifier.fillMaxWidth().clickable{onSelect(s)},
                    shape=RoundedCornerShape(16.dp)
                ){Column(Modifier.padding(14.dp)){
                Text(s.symbol?:s.name?:"نماد",fontWeight=FontWeight.Bold,fontSize=18.sp)
                if(!s.name.isNullOrBlank()&&s.name!=s.symbol) Text(s.name!!,fontSize=11.sp,color=Color.Gray)
            }}}
        }
    }

    @Composable
    private fun SettingsPage(onMarkets:()->Unit,onNames:()->Unit,onUpdate:()->Unit){
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(vertical=10.dp)
        ){
            item{
                Button(onClick=onMarkets,modifier=Modifier.fillMaxWidth()){Text("نوع اوراق و بازارهای مورد بررسی")}
            }
            item{
                FilledTonalButton(onClick=onNames,modifier=Modifier.fillMaxWidth()){Text("ترمیم دوباره نام نمادها")}
            }
            item{
                FilledTonalButton(onClick=onUpdate,modifier=Modifier.fillMaxWidth()){Text("همگام‌سازی داده‌های جدید")}
            }
            item{
                FilledTonalButton(onClick={startNextDayCheck()},modifier=Modifier.fillMaxWidth()){
                    Text("بررسی ماندگاری صف در روز معاملاتی بعد")
                }
            }
            item{
                Card(shape=RoundedCornerShape(18.dp)){
                    Text(
                        "اسکن ۵ثانیه‌ای فقط هنگام باز بودن برنامه اجرا می‌شود. در پس‌زمینه Android، رصد دوره‌ای با WorkManager ادامه دارد.",
                        Modifier.padding(14.dp),
                        fontSize=12.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun ProcessCard(title:String,status:String,done:Int,total:Int){
        Card(shape=RoundedCornerShape(18.dp)){
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(title,fontWeight=FontWeight.Bold)
                Text(status,fontSize=12.sp)
                if(total>0 && done<total){
                    LinearProgressIndicator(
                        progress={done.toFloat()/total.toFloat()},
                        modifier=Modifier.fillMaxWidth()
                    )
                    Text("${fa(done)} از ${fa(total)}",fontSize=12.sp)
                }
            }
        }
    }

    @Composable
    private fun Stat(title:String,value:Int,modifier:Modifier){
        Surface(modifier,shape=RoundedCornerShape(16.dp),color=Color.White.copy(alpha=.78f)){
            Column(Modifier.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Text(fa(value),fontSize=20.sp,fontWeight=FontWeight.Black)
                Text(title,fontSize=11.sp)
            }
        }
    }

    @Composable
    private fun ScoreBadge(score:Double){
        Surface(
            shape=RoundedCornerShape(18.dp),
            color=MaterialTheme.colorScheme.primaryContainer
        ){
            Text(
                "${fa(score.toInt())}/۱۰۰",
                Modifier.padding(horizontal=12.dp,vertical=10.dp),
                fontWeight=FontWeight.Black
            )
        }
    }

    @Composable
    private fun WeightedBar(label:String,raw:Double,weight:Int){
        val contribution=raw/100.0*weight
        Column{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text(label,fontSize=12.sp)
                Text(
                    "${fa(raw.toInt())}/۱۰۰  →  ${Jalali.digits(String.format(Locale.US,"%.1f",contribution))}/$weight",
                    fontSize=11.sp
                )
            }
            LinearProgressIndicator(
                progress={(raw/100.0).toFloat().coerceIn(0f,1f)},
                modifier=Modifier.fillMaxWidth()
            )
        }
    }

    @Composable
    private fun Empty(text:String){
        Card(shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().padding(top=10.dp)){
            Text(text,Modifier.padding(24.dp),color=Color.Gray)
        }
    }


    @Composable
    private fun MarketDialog(
        initialTypes:Set<String>,
        initialSegments:Set<String>,
        onDismiss:()->Unit,
        onSave:(Set<String>,Set<String>)->Unit
    ){
        var selectedTypes by remember{mutableStateOf(initialTypes)}
        var selectedSegments by remember{mutableStateOf(initialSegments)}

        val rows=listOf(
            listOf(MarketPrefs.TYPE_STOCK,MarketPrefs.TYPE_BASE),
            listOf(MarketPrefs.TYPE_HOUSING,MarketPrefs.TYPE_RIGHT),
            listOf(MarketPrefs.TYPE_BOND),
            listOf(MarketPrefs.TYPE_FUTURE,MarketPrefs.TYPE_FUND),
            listOf(MarketPrefs.TYPE_COMMODITY,MarketPrefs.TYPE_TAL),
            listOf(MarketPrefs.TYPE_ENERGY)
        )

        AlertDialog(
            onDismissRequest=onDismiss,
            title={Text("نوع اوراق")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                    rows.forEach{row->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.spacedBy(6.dp)
                        ){
                            row.forEach{type->
                                FilterChip(
                                    selected=selectedTypes.contains(type),
                                    onClick={
                                        selectedTypes=
                                            if(selectedTypes.contains(type)) selectedTypes-type
                                            else selectedTypes+type
                                    },
                                    label={Text(MarketPrefs.typeLabel(type),fontSize=11.sp)},
                                    modifier=Modifier.weight(1f)
                                )
                            }
                            if(row.size==1) Spacer(Modifier.weight(1f))
                        }
                    }

                    if(selectedTypes.contains(MarketPrefs.TYPE_BASE)){
                        HorizontalDivider()
                        Text("جزئیات بازار پایه",fontWeight=FontWeight.Bold,fontSize=12.sp)

                        listOf(
                            MarketPrefs.BASE_YELLOW,
                            MarketPrefs.BASE_ORANGE,
                            MarketPrefs.BASE_RED
                        ).forEach{seg->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment=Alignment.CenterVertically
                            ){
                                Checkbox(
                                    checked=selectedSegments.contains(seg),
                                    onCheckedChange={on->
                                        selectedSegments=
                                            if(on) selectedSegments+seg
                                            else selectedSegments-seg
                                    }
                                )
                                Text(MarketPrefs.label(seg))
                            }
                        }
                    }

                    Text(
                        "این فیلتر روی دریافت داده، تحلیل تاریخی، اسکن زنده و Paper Trading اعمال می‌شود.",
                        fontSize=10.sp,
                        color=Color.Gray
                    )
                }
            },
            confirmButton={
                TextButton(
                    onClick={
                        if(selectedTypes.isNotEmpty()){
                            onSave(selectedTypes,selectedSegments)
                        }
                    }
                ){Text("ذخیره")}
            },
            dismissButton={
                TextButton(onClick=onDismiss){Text("انصراف")}
            }
        )
    }


    private fun saveExtractionSelection(raw:String,years:Int){
        val symbols=raw
            .replace("،",",")
            .split(",")
            .map{it.trim()}
            .filter{it.isNotEmpty()}
            .toSet()
        getSharedPreferences("extract",Context.MODE_PRIVATE)
            .edit()
            .putStringSet("symbols",symbols)
            .putInt("years",years.coerceIn(1,5))
            .apply()
    }

    private fun startUpdate(){
        HistoricalWorker.start(this,false)
    }

    private fun startAnalyze(){
        val req=OneTimeWorkRequestBuilder<QueueAnalysisWorker>()
            .setConstraints(HistoricalWorker.networkConstraint())
            .setInputData(workDataOf("batchSize" to 120,"parallelism" to 4))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            QueueAnalysisWorker.ANALYSIS_CHAIN,ExistingWorkPolicy.REPLACE,req
        )
    }

    private fun startNextDayCheck(){
        val req=OneTimeWorkRequestBuilder<NextDayQueueWorker>()
            .setConstraints(HistoricalWorker.networkConstraint()).build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            NextDayQueueWorker.CHAIN,ExistingWorkPolicy.REPLACE,req
        )
    }

    private fun startNameRepair(){
        val req=OneTimeWorkRequestBuilder<MetadataWorker>()
            .setConstraints(HistoricalWorker.networkConstraint())
            .setInputData(workDataOf("batch" to 30))
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            MetadataWorker.CHAIN,ExistingWorkPolicy.REPLACE,req
        )
    }

    private fun fa(v:Int)=Jalali.digits(v.toString())

    private fun clock(ms:Long):String =
        Jalali.digits(SimpleDateFormat("HH:mm:ss",Locale.US).format(Date(ms)))

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

    private fun faPrice(v:Double):String =
        Jalali.digits(String.format(Locale.US,"%.0f",v))
}
