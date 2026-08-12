package com.borsapattern.app

object SymbolResolver {
    suspend fun ensure(
        dao:BorsaDao,
        api:TsetmcClient,
        insCode:String,
        rawSymbol:String?,
        rawName:String?,
        flow:Int?,
        board:String?
    ):SymbolEntity {
        val existing=dao.symbolByCode(insCode)
        val clean=cleanSymbol(rawSymbol,insCode)

        if(existing!=null && (!existing.symbol.isNullOrBlank() || !existing.name.isNullOrBlank())){
            if(existing.symbol.isNullOrBlank() && clean!=null){
                val fixed=existing.copy(symbol=clean)
                dao.upsertSymbols(listOf(fixed))
                return fixed
            }
            return existing
        }

        var symbol=clean
        var name=rawName
        var f=flow
        var b=board

        if(symbol.isNullOrBlank() && name.isNullOrBlank()){
            try{
                val o=api.jsonObjectFrom(api.instrumentInfoRaw(insCode),"instrumentInfo","instrument")
                if(o!=null){
                    symbol=cleanSymbol(firstString(o,"lVal18AFC","symbol","instrumentName"),insCode)
                    name=firstString(o,"lVal30","name","companyName","companyNamePersian")
                    f=firstInt(o,"flow") ?: f
                    b=firstString(o,"cgrValCotTitle","boardTitle","marketTitle") ?: b
                }
            }catch(_:Exception){}
        }

        val entity=SymbolEntity(
            insCode=insCode,
            symbol=symbol,
            name=name,
            flow=f,
            segment=MarketPrefs.classify(f,b),
            boardTitle=b
        )
        dao.upsertSymbols(listOf(entity))
        return entity
    }
}
