package com.example.bookmeta

data class BatchOptions(val fillOnlyEmpty:Boolean=true, val fields:Set<String> = emptySet())
object BatchEditor {
    fun apply(original:BookMeta, changes:BookMeta, options:BatchOptions):BookMeta {
        val result=original.copy()
        fun put(field:String, old:String, new:String, set:(String)->Unit) { if(field in options.fields && new.isNotBlank() && (!options.fillOnlyEmpty || old.isBlank())) set(new) }
        put("title",result.title,changes.title){result.title=it};put("author",result.author,changes.author){result.author=it};put("description",result.description,changes.description){result.description=it};put("publisher",result.publisher,changes.publisher){result.publisher=it};put("year",result.year,changes.year){result.year=it};put("isbn",result.isbn,changes.isbn){result.isbn=it};put("language",result.language,changes.language){result.language=it};put("subjects",result.subjects,changes.subjects){result.subjects=it};put("series",result.series,changes.series){result.series=it};put("seriesNumber",result.seriesNumber,changes.seriesNumber){result.seriesNumber=it};put("coverUri",result.coverUri,changes.coverUri){result.coverUri=it}
        return result
    }
}
