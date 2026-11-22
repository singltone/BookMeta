package com.example.bookmeta

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MetadataSearch {
    suspend fun search(query: String): BookMeta? = withContext(Dispatchers.IO) {
        if(query.isBlank()) return@withContext null
        val url="https://www.googleapis.com/books/v1/volumes?q=${java.net.URLEncoder.encode(query,"UTF-8")}&maxResults=5"
        val c=(URL(url).openConnection() as HttpURLConnection).apply { connectTimeout=10000;readTimeout=10000 }
        try {
            val root=JSONObject(c.inputStream.bufferedReader().readText()); val items=root.optJSONArray("items") ?: return@withContext null
            if(items.length()==0)return@withContext null
            val v=items.getJSONObject(0).optJSONObject("volumeInfo") ?: return@withContext null
            BookMeta(title=v.optString("title"), author=v.optJSONArray("authors")?.let { a->(0 until a.length()).joinToString(", "){a.getString(it)} }.orEmpty(), description=v.optString("description"), publisher=v.optString("publisher"), year=v.optString("publishedDate").take(4), isbn=v.optJSONArray("industryIdentifiers")?.let{ a->(0 until a.length()).map{a.getJSONObject(it)}.firstOrNull{it.optString("type").contains("ISBN_13")}?.optString("identifier") }.orEmpty(), language=v.optString("language"), subjects=v.optJSONArray("categories")?.let{a->(0 until a.length()).joinToString(", "){a.getString(it)} }.orEmpty())
        } finally { c.disconnect() }
    }
}
