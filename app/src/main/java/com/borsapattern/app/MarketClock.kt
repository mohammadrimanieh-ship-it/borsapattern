package com.borsapattern.app

import java.time.*
import java.time.format.DateTimeFormatter

object MarketClock {
    private val zone=ZoneId.of("Asia/Tehran")

    fun now():ZonedDateTime=ZonedDateTime.now(zone)

    fun todayGregorianInt():Int=
        now().format(DateTimeFormatter.BASIC_ISO_DATE).toInt()

    fun isRegularTradingDay():Boolean{
        return when(now().dayOfWeek){
            DayOfWeek.THURSDAY,DayOfWeek.FRIDAY -> false
            else -> true
        }
    }

    fun phase():String{
        if(!isRegularTradingDay()) return "روز تعطیل"
        val t=now().toLocalTime()
        return when{
            t.isBefore(LocalTime.of(8,45)) -> "بازار بسته"
            t.isBefore(LocalTime.of(9,0)) -> "پیش‌گشایش"
            !t.isAfter(LocalTime.of(12,30)) -> "بازار باز"
            else -> "بازار بسته"
        }
    }

    fun isLiveWindow():Boolean{
        if(!isRegularTradingDay()) return false
        val t=now().toLocalTime()
        return !t.isBefore(LocalTime.of(9,0)) && !t.isAfter(LocalTime.of(12,30))
    }

    fun currentJalaliDate():String=Jalali.fromGregorianInt(todayGregorianInt())

    fun clockText():String{
        val t=now().toLocalTime()
        return Jalali.digits(
            "%02d:%02d:%02d".format(t.hour,t.minute,t.second)
        )
    }

    fun dayLabel():String=when(now().dayOfWeek){
        DayOfWeek.SATURDAY->"شنبه"
        DayOfWeek.SUNDAY->"یکشنبه"
        DayOfWeek.MONDAY->"دوشنبه"
        DayOfWeek.TUESDAY->"سه‌شنبه"
        DayOfWeek.WEDNESDAY->"چهارشنبه"
        DayOfWeek.THURSDAY->"پنج‌شنبه"
        DayOfWeek.FRIDAY->"جمعه"
    }
}
