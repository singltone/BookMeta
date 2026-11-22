package com.example.bookmeta

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MetadataSearch {
    /** Compatibility helper: returns the best first match. */
    fun search(query: String): BookMeta? = searchAll(query).firstOrNull()

    /** Returns up to ten candidates so the user can choose the correct edition. */
    fun searchAll(query: String): List<BookMeta> {
        val cleaned = query.trim()
        if (cleaned.isBlank()) return emptyList()
        val isbn = cleaned.replace(Regex("[^0-9Xx]"), "")
        val searchTerm = if (isbn.length in 10..13 && isbn.all { it.isDigit() || it.equals('X', true) }) {
            "isbn:$isbn"
        } else cleaned
        val encoded = URLEncoder.encode(searchTerm, "UTF-8")
        val connection = URL(
            "https://www.googleapis.com/books/v1/volumes?q=$encoded&maxResults=10"
        ).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Google Books: HTTP $code")
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { index ->
                val info = items.optJSONObject(index)?.optJSONObject("volumeInfo") ?: return@mapNotNull null
                val title = info.optString("title").takeUnless { it == "null" }.orEmpty()
                if (title.isBlank()) return@mapNotNull null

                val authorsArray = info.optJSONArray("authors")
                val authors = (0 until (authorsArray?.length() ?: 0))
                    .map { authorsArray!!.optString(it) }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")

                val identifiers = info.optJSONArray("industryIdentifiers")
                var foundIsbn = ""
                for (i in 0 until (identifiers?.length() ?: 0)) {
                    val identifier = identifiers?.optJSONObject(i) ?: continue
                    val type = identifier.optString("type")
                    if (type == "ISBN_13" || (type == "ISBN_10" && foundIsbn.isBlank())) {
                        foundIsbn = identifier.optString("identifier")
                        if (type == "ISBN_13") break
                    }
                }

                val categories = info.optJSONArray("categories")
                val subjects = (0 until (categories?.length() ?: 0))
                    .map { categories!!.optString(it) }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                val published = info.optString("publishedDate").takeUnless { it == "null" }.orEmpty()

                BookMeta(
                    title = title,
                    author = authors,
                    description = info.optString("description").takeUnless { it == "null" }.orEmpty(),
                    publisher = info.optString("publisher").takeUnless { it == "null" }.orEmpty(),
                    year = Regex("\\d{4}").find(published)?.value.orEmpty(),
                    isbn = foundIsbn,
                    language = info.optString("language").takeUnless { it == "null" }.orEmpty(),
                    subjects = subjects
                    // Remote thumbnail URLs are intentionally not stored as coverUri:
                    // coverUri is a content URI used by the file writer.
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}
