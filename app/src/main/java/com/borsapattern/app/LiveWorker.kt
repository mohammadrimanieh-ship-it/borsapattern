package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject

class LiveWorker(ctx: Context, p: WorkerParameters): CoroutineWorker(ctx,p) {
    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext as BorsaApp
            val api = TsetmcClient()
            val arr = api.jsonArrayFrom(api.marketWatchRaw(),"marketwatch","marketWatch")
            val out = mutableListOf<LiveScoreEntity>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ins = firstString(o,"insCode","instrumentId") ?: continue
                val sym = firstString(o,"lVal18AFC","symbol")
                val last = firstDouble(o,"pl","pDrCotVal","lastPrice") ?: continue
                val y = firstDouble(o,"py","priceYesterday","yesterdayPrice") ?: continue
                val vol = firstDouble(o,"qTotTran5J","volume") ?: 0.0

                val priceMomentum = ((last/y)-1.0).coerceAtLeast(0.0) / 0.05
                val volumeAccel = (vol / 5_000_000.0).coerceIn(0.0,1.0)

                // تا وقتی BestLimits زنده و مدل تاریخی کامل نشده‌اند، این دو مؤلفه محافظه‌کارانه‌اند.
                val imbalance = 0.35
                val supplyDrop = 0.25
                val score = PatternEngine.scoreLive(priceMomentum,volumeAccel,imbalance,supplyDrop)

                val reason = buildString {
                    if (priceMomentum > 0.5) append("شتاب قیمت؛ ")
                    if (volumeAccel > 0.5) append("افزایش حجم؛ ")
                    if (isEmpty()) append("در حال پایش")
                }
                out += LiveScoreEntity(ins,sym,score,reason,System.currentTimeMillis())
            }
            app.db.dao().upsertScores(out)
            val top = out.maxByOrNull { it.score }
            if (top != null && top.score >= 80) {
                Notifications.show(applicationContext,top.symbol ?: top.insCode,top.score,top.reason)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
