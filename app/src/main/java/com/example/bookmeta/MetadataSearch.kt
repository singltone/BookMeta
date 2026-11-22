package com.example.bookmeta

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MetadataSearch {
    fun search(query: String): BookMeta? {
        if (query.isBlank()) return null
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val connection = (URL("https://www.googleapis.com/books/v1/volumes?q=$encoded&maxResults=10").openConnection() as HttpURLConnection)
        connection.connectTimeout=10000; connection.readTimeout=10000; connection.requestMethod="GET"
        try {
            val json=connection.inputStream.bufferedReader().use { it.readText() }
            val items=JSONObject(json).optJSONArray("items") ?: return null
            if(items.length()==0) return null
            val info=items.getJSONObject(0).optJSONObject("volumeInfo") ?: return null
            val authors=info.optJSONArray("authors")
            val author=(0 until (authors?.length() ?: 0)).joinToString(", "){authors!!.optString(it)}
            val ids=info.optJSONArray("industryIdentifiers")
            var isbn=""
            for(i in 0 until (ids?.length() ?: 0)) { val id=ids!!.optJSONObject(i); if(id?.optString("type")?.contains("ISBN") == true) { isbn=id.optString("identifier"); break } }
            val image=info.optJSONObject("imageLinks")?.optString("thumbnail","")?.replace("http://","https://") ?: ""
            return BookMeta(title=info.optString("title"),author=author,description=info.optString("description"),publisher=info.optString("publisher"),year=info.optString("publishedDate").take(4),isbn=isbn,language=info.optString("language"),subjects=(0 until (info.optJSONArray("categories")?.length() ?: 0)).joinToString(", "){info.optJSONArray("categories")!!.optString(it)},coverUri=image)
        } finally { connection.disconnect() }
    }
}
