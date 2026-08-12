package com.borsapattern.app

import org.json.JSONObject

fun firstString(o:JSONObject,vararg keys:String):String? {
    for(k in keys){
        if(o.has(k) && !o.isNull(k)){
            val v=o.optString(k,null)
            if(!v.isNullOrBlank()) return v
        }
    }
    val nested=o.optJSONObject("instrument")
    if(nested!=null){
        for(k in keys){
            if(nested.has(k) && !nested.isNull(k)){
                val v=nested.optString(k,null)
                if(!v.isNullOrBlank()) return v
            }
        }
    }
    return null
}

fun firstInt(o:JSONObject,vararg keys:String):Int? {
    for(k in keys){
        if(o.has(k) && !o.isNull(k)) return o.optInt(k)
    }
    val nested=o.optJSONObject("instrument")
    if(nested!=null){
        for(k in keys){
            if(nested.has(k) && !nested.isNull(k)) return nested.optInt(k)
        }
    }
    return null
}

fun firstDouble(o:JSONObject,vararg keys:String):Double? {
    for(k in keys){
        if(o.has(k) && !o.isNull(k)){
            val v=o.optDouble(k,Double.NaN)
            if(!v.isNaN()) return v
        }
    }
    return null
}

fun cleanSymbol(v:String?, insCode:String):String? {
    val s=v?.trim()?.replace("\u200c","")
    if(s.isNullOrBlank()) return null
    if(s==insCode) return null
    if(s.all{it.isDigit()}) return null
    return s
}
