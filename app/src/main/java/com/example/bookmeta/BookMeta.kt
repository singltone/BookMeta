package com.example.bookmeta

data class BookMeta(
    var title: String = "", var author: String = "", var description: String = "",
    var publisher: String = "", var year: String = "", var isbn: String = "",
    var language: String = "", var subjects: String = "", var series: String = "",
    var seriesNumber: String = "", var coverUri: String = ""
)
