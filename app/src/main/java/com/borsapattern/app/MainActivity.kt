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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
                primary=Color(0xFF5C35C8),
                secondary=Color(0xFF16B8A6),
                background=Color(0xFFF7F8FC),
                surface=Color.White,
                primaryContainer=Color(0xFFEFE8FF),
                secondaryContainer=Color(0xFFE7F7F4)
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
        var eligibleCount by remember{mutableStateOf(0)}
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
        var selectedStats by remember{mutableStateOf<SymbolDetailStats?>(null)}
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
                eligibleCount=app.db.dao().allSymbols().count{
                    val effectiveType=MarketPrefs.classifyType(
                        it.symbol,it.name,it.flow,it.boardTitle
                    )
                    MarketPrefs.isSignalUniverse(
                        it.segment,effectiveType,it.symbol,it.name
                    )
                }
                candidates=app.db.dao().candidateCount()
                confirmed=app.db.dao().confirmedCount()
                rejected=app.db.dao().rejectedCount()
                errors=app.db.dao().errorCount()
                latest=app.db.dao().latestMarketDate()
                scores=app.db.dao().topSignalScores()
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
            val s=selectedSymbol
            if(s==null){
                selectedSignals=emptyList()
                selectedStats=null
            }else{
                selectedSignals=runCatching{
                    app.db.dao().signalHistoryForSymbol(s.insCode,250)
                }.getOrDefault(emptyList())
                selectedStats=runCatching{
                    app.db.dao().symbolDetailStats(s.insCode)
                }.getOrNull()
            }
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
                Surface(
                    color=Color.White,
                    shadowElevation=2.dp
                ){
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(horizontal=18.dp,vertical=10.dp)
                    ){
                        Box(Modifier.fillMaxWidth()){
                            Text(
                                "Signal",
                                modifier=Modifier.align(Alignment.Center),
                                fontSize=28.sp,
                                fontWeight=FontWeight.Black,
                                color=Color(0xFF161827)
                            )
                            Text(
                                "◯",
                                modifier=Modifier.align(Alignment.CenterStart),
                                fontSize=22.sp,
                                color=Color(0xFF202232)
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.Center,
                            verticalAlignment=Alignment.CenterVertically
                        ){
                            Text(
                                "سیگنال هوشمند بورس",
                                color=MaterialTheme.colorScheme.primary,
                                fontSize=12.sp,
                                fontWeight=FontWeight.Bold
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "v1.8-test",
                                color=Color(0xFF666978),
                                fontSize=10.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ScrollableTabRow(
                            selectedTabIndex=section,
                            edgePadding=0.dp,
                            containerColor=Color.Transparent,
                            contentColor=MaterialTheme.colorScheme.primary,
                            divider={}
                        ){
                            Tab(section==0,{section=0},text={Text("سیگنال امروز",fontSize=12.sp)})
                            Tab(section==3,{section=3},text={Text("استخراج داده",fontSize=12.sp)})
                            Tab(section==1,{section=1},text={Text("بک‌تست روزانه",fontSize=12.sp)})
                            Tab(section==4,{section=4},text={Text("پیپر تریدینگ",fontSize=12.sp)})
                            Tab(section==2,{section=2},text={Text("جستجو",fontSize=12.sp)})
                        }
                    }
                }
            },
            bottomBar={
                NavigationBar(
                    containerColor=Color.White,
                    tonalElevation=4.dp
                ){
                    NavigationBarItem(
                        selected=section==0,
                        onClick={section=0},
                        icon={Text("↗",fontSize=20.sp)},
                        label={Text("سیگنال امروز",fontSize=9.sp)}
                    )
                    NavigationBarItem(
                        selected=section==3,
                        onClick={section=3},
                        icon={Text("▱",fontSize=20.sp)},
                        label={Text("استخراج داده",fontSize=9.sp)}
                    )
                    NavigationBarItem(
                        selected=section==1,
                        onClick={section=1},
                        icon={Text("□",fontSize=20.sp)},
                        label={Text("بک‌تست",fontSize=9.sp)}
                    )
                    NavigationBarItem(
                        selected=section==4,
                        onClick={section=4},
                        icon={Text("◒",fontSize=20.sp)},
                        label={Text("پیپر",fontSize=9.sp)}
                    )
                    NavigationBarItem(
                        selected=section==2,
                        onClick={section=2},
                        icon={Text("⌕",fontSize=20.sp)},
                        label={Text("جستجو",fontSize=9.sp)}
                    )
                }
            }
        ){padding->
            Box(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .padding(horizontal=12.dp)
            ){
                when(section){
                    0 -> DailySignals(scores,liveEnabled,{liveEnabled=it},lastLiveScan)
                    1 -> DailyBacktest(history)
                    2 -> SymbolSearchPage(
                        searchText,{searchText=it},searchResults,
                        selectedSymbol,selectedSignals,selectedStats,{selectedSymbol=it}
                    )
                    3 -> DataExtractionPage(
                        eligibleCount,syncStatus,syncDone,syncTotal,metadataStatus,
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
        var filter by remember{mutableIntStateOf(0)}
        val all=scores.sortedByDescending{it.score}
        val visible=when(filter){
            1 -> all.filter{it.score>=80}
            2 -> all.filter{it.score in 65.0..79.999}
            3 -> all.filter{it.score<65}
            else -> all
        }
        val avg=if(all.isEmpty()) 0 else all.map{it.score}.average().toInt()

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(top=12.dp,bottom=14.dp)
        ){
            item{
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ){
                    SummaryTile(
                        title="تعداد سیگنال‌ها",
                        value=fa(all.size),
                        bg=Color(0xFFE8F7F3),
                        modifier=Modifier.weight(1f)
                    )
                    SummaryTile(
                        title="میانگین امتیاز",
                        value=fa(avg),
                        bg=Color(0xFFEAF2FF),
                        modifier=Modifier.weight(1f)
                    )
                    SummaryTile(
                        title="آخرین بروزرسانی",
                        value=if(lastLiveScan!=null) clock(lastLiveScan) else "—",
                        bg=Color(0xFFF1E9FF),
                        modifier=Modifier.weight(1f)
                    )
                }
            }

            item{
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(6.dp),
                    verticalAlignment=Alignment.CenterVertically
                ){
                    SignalFilterChip(filter==0,{filter=0},"همه (${fa(all.size)})")
                    SignalFilterChip(filter==1,{filter=1},"قوی (${fa(all.count{it.score>=80})})")
                    SignalFilterChip(filter==2,{filter=2},"متوسط (${fa(all.count{it.score in 65.0..79.999})})")
                    SignalFilterChip(filter==3,{filter=3},"ضعیف (${fa(all.count{it.score<65})})")
                }
            }

            item{
                Card(
                    shape=RoundedCornerShape(18.dp),
                    colors=CardDefaults.cardColors(containerColor=Color(0xFFF9FAFD)),
                    border=BorderStroke(1.dp,Color(0xFFE5E7EF))
                ){
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column{
                            Text(
                                "اسکن و سیگنال فقط در بازه 09:00 تا 12:30",
                                fontSize=11.sp,
                                color=Color(0xFF6F7280)
                            )
                            Text(
                                if(liveEnabled) "رصد زنده فعال" else "رصد زنده خاموش",
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold,
                                color=if(liveEnabled) Color(0xFF168D68) else Color(0xFF8B8D98)
                            )
                        }
                        Switch(checked=liveEnabled,onCheckedChange=onLiveToggle)
                    }
                }
            }

            if(visible.isEmpty()){
                item{
                    Card(
                        shape=RoundedCornerShape(20.dp),
                        colors=CardDefaults.cardColors(containerColor=Color.White)
                    ){
                        Text(
                            "فعلاً سیگنالی در این فیلتر وجود ندارد.",
                            Modifier.fillMaxWidth().padding(22.dp),
                            textAlign=TextAlign.Center,
                            color=Color(0xFF777A87)
                        )
                    }
                }
            }else{
                items(visible){s->
                    SignalCard(s)
                }
            }

            item{
                Text(
                    "این اطلاعات صرفاً جهت تحلیل بوده و تایید قطعی خرید یا فروش نیست.",
                    modifier=Modifier.fillMaxWidth().padding(vertical=8.dp),
                    textAlign=TextAlign.Center,
                    fontSize=10.sp,
                    color=Color(0xFF8C8E98)
                )
            }
        }
    }

    @Composable
    private fun SummaryTile(
        title:String,
        value:String,
        bg:Color,
        modifier:Modifier=Modifier
    ){
        Card(
            modifier=modifier.height(116.dp),
            shape=RoundedCornerShape(18.dp),
            colors=CardDefaults.cardColors(containerColor=bg)
        ){
            Column(
                Modifier.fillMaxSize().padding(10.dp),
                horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.Center
            ){
                Text(title,fontSize=10.sp,color=Color(0xFF555968),textAlign=TextAlign.Center)
                Spacer(Modifier.height(7.dp))
                Text(value,fontSize=22.sp,fontWeight=FontWeight.Black,color=Color(0xFF171927))
            }
        }
    }

    @Composable
    private fun SignalFilterChip(
        selected:Boolean,
        onClick:()->Unit,
        text:String
    ){
        FilterChip(
            selected=selected,
            onClick=onClick,
            label={Text(text,fontSize=10.sp)},
            colors=FilterChipDefaults.filterChipColors(
                selectedContainerColor=MaterialTheme.colorScheme.primary,
                selectedLabelColor=Color.White,
                containerColor=Color.White
            ),
            border=FilterChipDefaults.filterChipBorder(
                enabled=true,
                selected=selected,
                borderColor=Color(0xFFE1E3EA),
                selectedBorderColor=MaterialTheme.colorScheme.primary
            )
        )
    }

    @Composable
    private fun SignalCard(s:LiveScoreEntity){
        val score=s.score.toInt()
        val strong=score>=80
        val medium=score>=65
        val badge=when{
            strong -> Color(0xFFDFF5E8)
            medium -> Color(0xFFFFF0D9)
            else -> Color(0xFFF2E8E8)
        }
        val badgeText=when{
            strong -> Color(0xFF118658)
            medium -> Color(0xFFD67A00)
            else -> Color(0xFFA85A5A)
        }

        Card(
            modifier=Modifier.fillMaxWidth(),
            shape=RoundedCornerShape(20.dp),
            colors=CardDefaults.cardColors(containerColor=Color.White),
            border=BorderStroke(1.dp,Color(0xFFE4E6ED))
        ){
            Row(
                Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=13.dp),
                verticalAlignment=Alignment.CenterVertically
            ){
                Column(Modifier.weight(1.2f)){
                    Text(
                        s.symbol?:"در حال تکمیل نام",
                        fontSize=17.sp,
                        fontWeight=FontWeight.Black,
                        color=Color(0xFF1C1E29)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when{
                            strong -> "سیگنال قوی"
                            medium -> "سیگنال متوسط"
                            else -> "تحت نظر"
                        },
                        fontSize=10.sp,
                        color=Color(0xFF777A86)
                    )
                }

                Column(
                    Modifier.weight(.8f),
                    horizontalAlignment=Alignment.CenterHorizontally
                ){
                    Surface(
                        shape=RoundedCornerShape(10.dp),
                        color=badge
                    ){
                        Text(
                            fa(score),
                            Modifier.padding(horizontal=10.dp,vertical=5.dp),
                            color=badgeText,
                            fontWeight=FontWeight.Bold
                        )
                    }
                    Text("امتیاز",fontSize=9.sp,color=Color.Gray)
                }

                Column(
                    Modifier.weight(.9f),
                    horizontalAlignment=Alignment.CenterHorizontally
                ){
                    Text(clock(s.updatedAt),fontSize=13.sp,fontWeight=FontWeight.Bold)
                    Text("زمان سیگنال",fontSize=9.sp,color=Color.Gray)
                }

                Text(
                    if(strong)"▲" else if(medium)"●" else "•",
                    color=if(strong) Color(0xFF159A63) else if(medium) Color(0xFFE28B14) else Color(0xFF9A9CA6),
                    fontSize=15.sp
                )
            }
        }
    }

    @Composable
    private fun DailyBacktest(history:List<QueueHistoryRow>){
        val grouped=history.groupBy{it.date}.toSortedMap(compareByDescending{it})

        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(12.dp),
            contentPadding=PaddingValues(top=12.dp,bottom=18.dp)
        ){
            item{
                PageHero(
                    eyebrow="BACKTEST",
                    title="بک‌تست روزانه",
                    subtitle="نتیجه واقعی سیگنال‌های تاریخی، روز به روز"
                )
            }

            if(grouped.isEmpty()){
                item{ PolishedEmpty("هنوز نتیجه بک‌تست روزانه ثبت نشده است.") }
            }else{
                grouped.forEach{(date,rows)->
                    item{
                        Card(
                            shape=RoundedCornerShape(22.dp),
                            colors=CardDefaults.cardColors(containerColor=Color.White),
                            border=BorderStroke(1.dp,Color(0xFFE6E8F0))
                        ){
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement=Arrangement.spacedBy(10.dp)
                            ){
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement=Arrangement.SpaceBetween,
                                    verticalAlignment=Alignment.CenterVertically
                                ){
                                    Text(
                                        Jalali.fromGregorianInt(date),
                                        fontSize=18.sp,
                                        fontWeight=FontWeight.Black,
                                        color=Color(0xFF171927)
                                    )
                                    Surface(
                                        color=Color(0xFFF0EBFF),
                                        shape=RoundedCornerShape(12.dp)
                                    ){
                                        Text(
                                            "${fa(rows.size)} سیگنال",
                                            Modifier.padding(horizontal=10.dp,vertical=5.dp),
                                            color=MaterialTheme.colorScheme.primary,
                                            fontSize=11.sp,
                                            fontWeight=FontWeight.Bold
                                        )
                                    }
                                }

                                rows.forEach{s->
                                    Surface(
                                        color=Color(0xFFF9FAFC),
                                        shape=RoundedCornerShape(16.dp)
                                    ){
                                        Column(
                                            Modifier.fillMaxWidth().padding(12.dp),
                                            verticalArrangement=Arrangement.spacedBy(4.dp)
                                        ){
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement=Arrangement.SpaceBetween
                                            ){
                                                Text(
                                                    s.symbol?:"در حال تکمیل نام",
                                                    fontWeight=FontWeight.Bold,
                                                    fontSize=16.sp
                                                )
                                                Text(
                                                    "${fa(s.score.toInt())}/۱۰۰",
                                                    color=MaterialTheme.colorScheme.primary,
                                                    fontWeight=FontWeight.Black
                                                )
                                            }
                                            Text(
                                                "هشدار ${fmtTime(s.signalTime)}  •  صف ${fmtTime(s.eventTime)}",
                                                fontSize=11.sp,
                                                color=Color(0xFF727583)
                                            )
                                            Text(
                                                when(s.nextDayQueueStatus){
                                                    "QUEUE_AGAIN" -> "روز بعد هم صف خرید ماند ✓"
                                                    "NOT_QUEUE_NEXT_DAY" -> "روز بعد صف خرید نماند"
                                                    "NO_NEXT_DAY" -> "داده روز معاملاتی بعد موجود نیست"
                                                    else -> "روز بعد هنوز بررسی نشده"
                                                },
                                                fontSize=11.sp,
                                                color=when(s.nextDayQueueStatus){
                                                    "QUEUE_AGAIN" -> Color(0xFF118658)
                                                    "NOT_QUEUE_NEXT_DAY" -> Color(0xFFB85A5A)
                                                    else -> Color(0xFF777A87)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DataExtractionPage(
        eligibleCount:Int,
        syncStatus:String,syncDone:Int,syncTotal:Int,metadataStatus:String,
        onMarkets:()->Unit,onNames:()->Unit,
        onStart:(String,Int)->Unit,onAnalyze:()->Unit,onNextDay:()->Unit
    ){
        var symbolsText by remember{mutableStateOf("")}
        var years by remember{mutableIntStateOf(5)}
        var showConfirm by remember{mutableStateOf(false)}

        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(12.dp),
            contentPadding=PaddingValues(top=12.dp,bottom=18.dp)
        ){
            item{
                PageHero(
                    eyebrow="DATASET",
                    title="استخراج داده",
                    subtitle="فقط سهام بورس، فرابورس، بازار پایه و صندوق‌های اهرمی"
                )
            }

            item{
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ){
                    SummaryTile(
                        title="Universe نهایی",
                        value=fa(eligibleCount),
                        bg=Color(0xFFE9F7F3),
                        modifier=Modifier.weight(1f)
                    )
                    SummaryTile(
                        title="رکورد ذخیره‌شده",
                        value=if(syncDone>0) fa(syncDone) else "—",
                        bg=Color(0xFFEEF2FF),
                        modifier=Modifier.weight(1f)
                    )
                    SummaryTile(
                        title="بازه انتخابی",
                        value="${fa(years)} سال",
                        bg=Color(0xFFF2ECFF),
                        modifier=Modifier.weight(1f)
                    )
                }
            }

            item{
                PolishedCard{
                    Text("۱. محدوده بازار",fontSize=15.sp,fontWeight=FontWeight.Black)
                    Text(
                        "گروه‌های نامرتبط حذف شده‌اند و در شمارش Universe هم وارد نمی‌شوند.",
                        fontSize=11.sp,color=Color(0xFF747785)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick=onMarkets,
                        modifier=Modifier.fillMaxWidth(),
                        shape=RoundedCornerShape(14.dp)
                    ){
                        Text("انتخاب بورس / فرابورس / بازار پایه / اهرمی")
                    }
                }
            }

            item{
                PolishedCard{
                    Text("۲. نمادهای خاص",fontSize=15.sp,fontWeight=FontWeight.Black)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value=symbolsText,
                        onValueChange={symbolsText=it},
                        modifier=Modifier.fillMaxWidth(),
                        shape=RoundedCornerShape(16.dp),
                        singleLine=false,
                        minLines=2,
                        label={Text("اختیاری")},
                        placeholder={Text("مثال: وبملت، خودرو، شستا")}
                    )
                    Text(
                        "اگر خالی باشد، همه نمادهای Universe انتخاب‌شده استخراج می‌شوند.",
                        fontSize=10.sp,color=Color(0xFF858894)
                    )
                }
            }

            item{
                PolishedCard{
                    Text("۳. بازه تاریخی",fontSize=15.sp,fontWeight=FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)
                    ){
                        (1..5).forEach{y->
                            FilterChip(
                                selected=years==y,
                                onClick={years=y},
                                label={Text("${fa(y)} سال",fontSize=10.sp)},
                                modifier=Modifier.weight(1f),
                                colors=FilterChipDefaults.filterChipColors(
                                    selectedContainerColor=MaterialTheme.colorScheme.primary,
                                    selectedLabelColor=Color.White,
                                    containerColor=Color(0xFFF7F7FA)
                                )
                            )
                        }
                    }
                }
            }

            item{
                Button(
                    onClick={showConfirm=true},
                    modifier=Modifier.fillMaxWidth().height(52.dp),
                    shape=RoundedCornerShape(16.dp)
                ){
                    Text("بررسی و شروع استخراج",fontWeight=FontWeight.Bold)
                }
            }

            item{
                ProcessCard("وضعیت استخراج",syncStatus,syncDone,syncTotal)
            }

            item{
                PolishedCard{
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column(Modifier.weight(1f)){
                            Text("نام نمادها",fontWeight=FontWeight.Bold)
                            Text(metadataStatus,fontSize=11.sp,color=Color(0xFF747785))
                        }
                        TextButton(onClick=onNames){
                            Text("بررسی ناقص‌ها")
                        }
                    }
                    Text(
                        "نام‌های موجود دوباره دانلود نمی‌شوند؛ فقط نمادهای جدید یا رکوردهای ناقص ترمیم می‌شوند.",
                        fontSize=10.sp,color=Color(0xFF8B8D98)
                    )
                }
            }

            item{
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ){
                    FilledTonalButton(
                        onClick=onAnalyze,
                        modifier=Modifier.weight(1f),
                        shape=RoundedCornerShape(14.dp)
                    ){Text("تحلیل تاریخی",fontSize=11.sp)}
                    FilledTonalButton(
                        onClick=onNextDay,
                        modifier=Modifier.weight(1f),
                        shape=RoundedCornerShape(14.dp)
                    ){Text("صف روز بعد",fontSize=11.sp)}
                }
            }
        }

        if(showConfirm){
            AlertDialog(
                onDismissRequest={showConfirm=false},
                shape=RoundedCornerShape(24.dp),
                title={Text("تایید شروع استخراج",fontWeight=FontWeight.Black)},
                text={
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("Universe فعلی: ${fa(eligibleCount)} نماد")
                        Text("بازه: ${fa(years)} سال")
                        Text(
                            if(symbolsText.isBlank())
                                "نماد خاصی وارد نشده؛ همه Universe انتخاب‌شده بررسی می‌شود."
                            else "نمادهای انتخابی: $symbolsText",
                            fontSize=12.sp,color=Color(0xFF666977)
                        )
                        Text(
                            "تا زمانی که «تایید و شروع» را نزنی هیچ Worker استخراجی اجرا نمی‌شود.",
                            fontSize=11.sp,color=MaterialTheme.colorScheme.primary,
                            fontWeight=FontWeight.Bold
                        )
                    }
                },
                confirmButton={
                    Button(onClick={
                        showConfirm=false
                        onStart(symbolsText,years)
                    }){Text("تایید و شروع")}
                },
                dismissButton={
                    TextButton(onClick={showConfirm=false}){Text("انصراف")}
                }
            )
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
        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(12.dp),
            contentPadding=PaddingValues(top=12.dp,bottom=18.dp)
        ){
            item{
                PageHero(
                    eyebrow="PAPER",
                    title="پیپر تریدینگ",
                    subtitle="آزمایش استراتژی بدون پول واقعی"
                )
            }

            if(items.isEmpty()){
                item{
                    PolishedEmpty("هنوز معامله آزمایشی ثبت نشده است. سیگنال‌های قوی بعداً می‌توانند اینجا وارد Paper Trading شوند.")
                }
            }else{
                items(items){t->
                    val open=t.status=="OPEN"
                    Card(
                        shape=RoundedCornerShape(20.dp),
                        colors=CardDefaults.cardColors(containerColor=Color.White),
                        border=BorderStroke(1.dp,Color(0xFFE5E7EE))
                    ){
                        Column(
                            Modifier.padding(15.dp),
                            verticalArrangement=Arrangement.spacedBy(8.dp)
                        ){
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement=Arrangement.SpaceBetween,
                                verticalAlignment=Alignment.CenterVertically
                            ){
                                Text(
                                    t.symbol?:"نماد",
                                    fontSize=18.sp,
                                    fontWeight=FontWeight.Black
                                )
                                Surface(
                                    color=if(open) Color(0xFFE3F6EA) else Color(0xFFF0F1F5),
                                    shape=RoundedCornerShape(10.dp)
                                ){
                                    Text(
                                        if(open)"باز" else "بسته",
                                        Modifier.padding(horizontal=10.dp,vertical=5.dp),
                                        color=if(open) Color(0xFF118658) else Color(0xFF6D707D),
                                        fontSize=11.sp,
                                        fontWeight=FontWeight.Bold
                                    )
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement=Arrangement.spacedBy(8.dp)
                            ){
                                MetricPill("ورود",faPrice(t.entryPrice),Modifier.weight(1f))
                                MetricPill("فعلی/خروج",faPrice(t.currentPrice),Modifier.weight(1f))
                                MetricPill(
                                    "بازده",
                                    Jalali.digits(String.format(Locale.US,"%.2f%%",t.pnlPct)),
                                    Modifier.weight(1f)
                                )
                            }
                            Text(
                                "امتیاز ورود ${fa(t.entryScore.toInt())}/۱۰۰",
                                fontSize=11.sp,
                                color=Color(0xFF777A86)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SymbolSearchPage(
        query:String,onQuery:(String)->Unit,results:List<SymbolEntity>,
        selected:SymbolEntity?,signals:List<SymbolSignalRow>,
        stats:SymbolDetailStats?,onSelect:(SymbolEntity)->Unit
    ){
        if(selected!=null){
            LazyColumn(
                verticalArrangement=Arrangement.spacedBy(12.dp),
                contentPadding=PaddingValues(top=12.dp,bottom=18.dp)
            ){
                item{
                    PageHero(
                        eyebrow="SYMBOL",
                        title=selected.symbol?:selected.name?:"نماد",
                        subtitle=selected.name?.takeIf{it!=selected.symbol} ?: "جزئیات تاریخی نماد"
                    )
                }

                item{
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ){
                        SummaryTile(
                            title="رکورد تاریخی",
                            value=fa(stats?.recordCount ?: 0),
                            bg=Color(0xFFEAF2FF),
                            modifier=Modifier.weight(1f)
                        )
                        SummaryTile(
                            title="اولین داده",
                            value=stats?.firstDate?.let{Jalali.fromGregorianInt(it)} ?: "—",
                            bg=Color(0xFFF2ECFF),
                            modifier=Modifier.weight(1f)
                        )
                        SummaryTile(
                            title="آخرین داده",
                            value=stats?.lastDate?.let{Jalali.fromGregorianInt(it)} ?: "—",
                            bg=Color(0xFFE9F7F3),
                            modifier=Modifier.weight(1f)
                        )
                    }
                }

                if(signals.isEmpty()){
                    item{
                        PolishedEmpty(
                            "هنوز سیگنال تاریخی ثبت نشده. بعد از استخراج و تحلیل داده‌های همین نماد، زمان‌های هشدار اینجا نمایش داده می‌شوند."
                        )
                    }
                }else{
                    items(signals){s->
                        Card(
                            shape=RoundedCornerShape(18.dp),
                            colors=CardDefaults.cardColors(containerColor=Color.White),
                            border=BorderStroke(1.dp,Color(0xFFE6E8EF))
                        ){
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement=Arrangement.spacedBy(5.dp)
                            ){
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement=Arrangement.SpaceBetween
                                ){
                                    Text(
                                        Jalali.fromGregorianInt(s.date),
                                        fontWeight=FontWeight.Black
                                    )
                                    Text(
                                        "${fa(s.score.toInt())}/۱۰۰",
                                        color=MaterialTheme.colorScheme.primary,
                                        fontWeight=FontWeight.Black
                                    )
                                }
                                Text(
                                    "هشدار ${fmtTime(s.signalTime)}  •  صف/رخداد ${fmtTime(s.eventTime)}",
                                    fontSize=11.sp,
                                    color=Color(0xFF737684)
                                )
                                Text(
                                    when(s.nextDayQueueStatus){
                                        "QUEUE_AGAIN"->"روز بعد هم صف خرید ماند ✓"
                                        "NOT_QUEUE_NEXT_DAY"->"روز بعد صف نماند"
                                        "NO_NEXT_DAY"->"داده روز بعد موجود نیست"
                                        else->"روز بعد هنوز بررسی نشده"
                                    },
                                    fontSize=11.sp,
                                    color=when(s.nextDayQueueStatus){
                                        "QUEUE_AGAIN"->Color(0xFF118658)
                                        "NOT_QUEUE_NEXT_DAY"->Color(0xFFB85A5A)
                                        else->Color(0xFF777A86)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            return
        }

        LazyColumn(
            verticalArrangement=Arrangement.spacedBy(10.dp),
            contentPadding=PaddingValues(top=12.dp,bottom=18.dp)
        ){
            item{
                PageHero(
                    eyebrow="SEARCH",
                    title="جستجوی نماد",
                    subtitle="نام نماد را پیدا کن و سابقه سیگنال‌هایش را ببین"
                )
            }
            item{
                OutlinedTextField(
                    value=query,
                    onValueChange=onQuery,
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(18.dp),
                    singleLine=true,
                    label={Text("نام نماد")},
                    placeholder={Text("مثال: وبملت")}
                )
            }

            if(query.isNotBlank() && results.isEmpty()){
                item{PolishedEmpty("نمادی با این عبارت پیدا نشد.")}
            }

            items(results){s->
                Card(
                    modifier=Modifier.fillMaxWidth().clickable{onSelect(s)},
                    shape=RoundedCornerShape(18.dp),
                    colors=CardDefaults.cardColors(containerColor=Color.White),
                    border=BorderStroke(1.dp,Color(0xFFE6E8EF))
                ){
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column(Modifier.weight(1f)){
                            Text(
                                s.symbol?:s.name?:"نماد",
                                fontWeight=FontWeight.Black,
                                fontSize=17.sp
                            )
                            if(!s.name.isNullOrBlank() && s.name!=s.symbol){
                                Text(s.name!!,fontSize=11.sp,color=Color(0xFF777A86))
                            }
                        }
                        Surface(
                            color=Color(0xFFF0EBFF),
                            shape=RoundedCornerShape(12.dp)
                        ){
                            Text(
                                "جزئیات",
                                Modifier.padding(horizontal=10.dp,vertical=6.dp),
                                color=MaterialTheme.colorScheme.primary,
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold
                            )
                        }
                    }
                }
            }
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
    private fun PageHero(eyebrow:String,title:String,subtitle:String){
        Card(
            shape=RoundedCornerShape(24.dp),
            colors=CardDefaults.cardColors(containerColor=Color(0xFF211B37))
        ){
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement=Arrangement.spacedBy(5.dp)
            ){
                Text(
                    eyebrow,
                    fontSize=10.sp,
                    fontWeight=FontWeight.Bold,
                    color=Color(0xFFBCAEFF)
                )
                Text(
                    title,
                    fontSize=23.sp,
                    fontWeight=FontWeight.Black,
                    color=Color.White
                )
                Text(
                    subtitle,
                    fontSize=11.sp,
                    color=Color(0xFFD5D0E4)
                )
            }
        }
    }

    @Composable
    private fun PolishedCard(content:@Composable ColumnScope.()->Unit){
        Card(
            shape=RoundedCornerShape(20.dp),
            colors=CardDefaults.cardColors(containerColor=Color.White),
            border=BorderStroke(1.dp,Color(0xFFE5E7EE))
        ){
            Column(
                Modifier.fillMaxWidth().padding(15.dp),
                verticalArrangement=Arrangement.spacedBy(6.dp),
                content=content
            )
        }
    }

    @Composable
    private fun PolishedEmpty(text:String){
        Card(
            shape=RoundedCornerShape(20.dp),
            colors=CardDefaults.cardColors(containerColor=Color.White),
            border=BorderStroke(1.dp,Color(0xFFE6E8EF))
        ){
            Text(
                text,
                Modifier.fillMaxWidth().padding(22.dp),
                textAlign=TextAlign.Center,
                fontSize=12.sp,
                color=Color(0xFF7A7D89)
            )
        }
    }

    @Composable
    private fun MetricPill(title:String,value:String,modifier:Modifier=Modifier){
        Surface(
            modifier=modifier,
            color=Color(0xFFF7F8FB),
            shape=RoundedCornerShape(14.dp)
        ){
            Column(
                Modifier.padding(horizontal=8.dp,vertical=9.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ){
                Text(title,fontSize=9.sp,color=Color(0xFF858793))
                Text(value,fontSize=12.sp,fontWeight=FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun ProcessCard(title:String,status:String,done:Int,total:Int){
        Card(
            shape=RoundedCornerShape(20.dp),
            colors=CardDefaults.cardColors(containerColor=Color.White),
            border=BorderStroke(1.dp,Color(0xFFE5E7EE))
        ){
            Column(
                Modifier.padding(15.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)
            ){
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.SpaceBetween
                ){
                    Text(title,fontWeight=FontWeight.Bold)
                    if(total>0){
                        Text("${fa(done)} / ${fa(total)}",fontSize=11.sp,color=Color(0xFF777A86))
                    }
                }
                Text(status,fontSize=11.sp,color=Color(0xFF6F7280))
                if(total>0 && done<total){
                    LinearProgressIndicator(
                        progress={done.toFloat()/total.toFloat()},
                        modifier=Modifier.fillMaxWidth().height(7.dp),
                        trackColor=Color(0xFFEDEEF3)
                    )
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
        var selectedTypes by remember{mutableStateOf(initialTypes.intersect(MarketPrefs.allTypes))}
        var selectedSegments by remember{mutableStateOf(initialSegments.intersect(MarketPrefs.allSegments))}

        AlertDialog(
            onDismissRequest=onDismiss,
            shape=RoundedCornerShape(26.dp),
            containerColor=Color.White,
            title={
                Column{
                    Text("محدوده استخراج",fontWeight=FontWeight.Black,fontSize=20.sp)
                    Text(
                        "فقط ابزارهای سازگار با مدل Signal",
                        fontSize=11.sp,
                        color=Color(0xFF777A86)
                    )
                }
            },
            text={
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("نوع ابزار",fontSize=12.sp,fontWeight=FontWeight.Bold)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)
                    ){
                        listOf(
                            MarketPrefs.TYPE_STOCK,
                            MarketPrefs.TYPE_BASE,
                            MarketPrefs.TYPE_FUND
                        ).forEach{type->
                            FilterChip(
                                selected=selectedTypes.contains(type),
                                onClick={
                                    selectedTypes=
                                        if(selectedTypes.contains(type)) selectedTypes-type
                                        else selectedTypes+type
                                },
                                label={Text(MarketPrefs.typeLabel(type),fontSize=10.sp)},
                                colors=FilterChipDefaults.filterChipColors(
                                    selectedContainerColor=MaterialTheme.colorScheme.primary,
                                    selectedLabelColor=Color.White
                                )
                            )
                        }
                    }

                    HorizontalDivider(color=Color(0xFFEDEEF2))
                    Text("بازار",fontSize=12.sp,fontWeight=FontWeight.Bold)

                    listOf(
                        MarketPrefs.BOURSE,
                        MarketPrefs.FARABOURSE,
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
                                        if(on) selectedSegments+seg else selectedSegments-seg
                                }
                            )
                            Text(MarketPrefs.label(seg),fontSize=12.sp)
                        }
                    }

                    Surface(
                        color=Color(0xFFF5F2FF),
                        shape=RoundedCornerShape(14.dp)
                    ){
                        Text(
                            "تسهیلات مسکن، حق تقدم، اوراق بدهی، اختیار معامله، آتی، بورس کالا، انرژی و TAL در این مدل حذف شده‌اند.",
                            Modifier.padding(10.dp),
                            fontSize=10.sp,
                            color=Color(0xFF625C76)
                        )
                    }
                }
            },
            confirmButton={
                Button(
                    onClick={
                        onSave(
                            if(selectedTypes.isEmpty()) MarketPrefs.allTypes else selectedTypes,
                            if(selectedSegments.isEmpty()) MarketPrefs.allSegments else selectedSegments
                        )
                    },
                    shape=RoundedCornerShape(14.dp)
                ){Text("ذخیره انتخاب‌ها")}
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
