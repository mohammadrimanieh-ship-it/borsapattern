package com.borsapattern.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class HistoricalWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    private val api = TsetmcClient()
    private val dao get() = (applicationContext as BorsaApp).db.dao()

    override suspend fun doWork(): Result {
        return try {
            setProgress(androidx.work.workDataOf("stage" to "دریافت فهرست نمادها", "progress" to 1))
            val arr = api.jsonArrayFrom(api.marketWatchRaw(), "marketwatch", "marketWatch")
            val symbols = mutableListOf<SymbolEntity>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ins = firstString(o, "insCode","instrumentId") ?: continue
                symbols += SymbolEntity(ins, firstString(o,"lVal18AFC","symbol"), firstString(o,"lVal30","name"))
            }
            dao.upsertSymbols(symbols)

            val cutoff = LocalDate.now().minusDays(370)
            val dailyAll = mutableListOf<DailyEntity>()
            symbols.forEachIndexed { idx, s ->
                setProgress(androidx.work.workDataOf(
                    "stage" to "تاریخچه ${s.symbol ?: s.insCode}",
                    "progress" to ((idx+1)*70 / maxOf(1,symbols.size))
                ))
                try {
                    val d = api.jsonArrayFrom(api.dailyRaw(s.insCode), "closingPriceDaily")
                    val batch = mutableListOf<DailyEntity>()
                    for (j in 0 until d.length()) {
                        val o = d.optJSONObject(j) ?: continue
                        val date = firstInt(o,"dEven","date") ?: continue
                        val parsed = runCatching {
                            LocalDate.parse(date.toString(), DateTimeFormatter.BASIC_ISO_DATE)
                        }.getOrNull() ?: continue
                        if (parsed.isBefore(cutoff)) continue
                        batch += DailyEntity(
                            s.insCode, date,
                            firstDouble(o,"priceMax","pmax","pMax"),
                            firstDouble(o,"pDrCotVal","pl","lastPrice"),
                            firstDouble(o,"priceYesterday","py","yesterdayPrice"),
                            firstDouble(o,"qTotTran5J","volume"),
                            firstDouble(o,"qTotCap","value")
                        )
                    }
                    dao.upsertDaily(batch)
                } catch (_: Exception) {}
            }

            setProgress(androidx.work.workDataOf("stage" to "تشخیص اولیه رویدادها", "progress" to 85))
            PatternEngine.seedInitialEvents((applicationContext as BorsaApp).db)

            setProgress(androidx.work.workDataOf("stage" to "تمام شد", "progress" to 100))
            Result.success()
        } catch (e: Exception) {
            Result.failure(androidx.work.workDataOf("error" to (e.message ?: "خطا")))
        }
    }
}

fun firstString(o: JSONObject, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { k -> if (o.has(k) && !o.isNull(k)) o.optString(k,null) else null }

fun firstInt(o: JSONObject, vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { k -> if (o.has(k) && !o.isNull(k)) o.optInt(k) else null }

fun firstDouble(o: JSONObject, vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { k -> if (o.has(k) && !o.isNull(k)) o.optDouble(k) else null }
