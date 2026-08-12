package com.borsapattern.app

import android.content.Context

object MarketPrefs {
    const val BOURSE="BOURSE"
    const val FARABOURSE="FARABOURSE"
    const val BASE_YELLOW="BASE_YELLOW"
    const val BASE_ORANGE="BASE_ORANGE"
    const val BASE_RED="BASE_RED"
    const val OTHER="OTHER"

    val all=setOf(BOURSE,FARABOURSE,BASE_YELLOW,BASE_ORANGE,BASE_RED,OTHER)

    fun selected(ctx:Context):Set<String>{
        val p=ctx.getSharedPreferences("market_filters",Context.MODE_PRIVATE)
        return p.getStringSet("segments",null)?.toSet() ?: all
    }

    fun save(ctx:Context,segments:Set<String>){
        ctx.getSharedPreferences("market_filters",Context.MODE_PRIVATE)
            .edit().putStringSet("segments",segments).apply()
    }

    fun label(s:String)=when(s){
        BOURSE -> "بورس"
        FARABOURSE -> "فرابورس"
        BASE_YELLOW -> "پایه زرد"
        BASE_ORANGE -> "پایه نارنجی"
        BASE_RED -> "پایه قرمز"
        else -> "سایر"
    }

    fun classify(flow:Int?, board:String?):String{
        val b=(board?:"").trim()
        return when{
            b.contains("زرد") -> BASE_YELLOW
            b.contains("نارنجی") -> BASE_ORANGE
            b.contains("قرمز") -> BASE_RED
            flow==1 -> BOURSE
            flow==2 -> FARABOURSE
            flow==4 -> OTHER
            else -> OTHER
        }
    }
}
