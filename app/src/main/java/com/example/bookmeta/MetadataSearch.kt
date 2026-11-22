package com.example.bookmeta

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MetadataSearchException(
    val httpCode: Int,
    message: String
) : IOException(message)

object MetadataSearch {
    private var lastRequestAt = 0L
    private const val MIN_REQUEST_INTERVAL_MS = 1500L
    private const val MAX_RETRIES = 3

    /** Compatibility helper: returns the best first match. */
    fun search(query: String): BookMeta? = searchAll(query).firstOrNull()

    /** Requests are paced globally; temporary Google Books rate limits are retried. */
    @Synchronized
    fun searchAll(query: String): List<BookMeta> {
        val cleaned = query.trim()
        if (cleaned.isBlank()) return emptyList()
        val isbn = cleaned.replace(Regex("[^0-9Xx]"), "")
        val searchTerm = if (isbn.length in 10..13 && isbn.all { it.isDigit() || it.equals('X', true) }) {
            "isbn:$isbn"
        } else cleaned
        val encoded = URLEncoder.encode(searchTerm, "UTF-8")

        var attempt = 0
        val body = while (true) {
            val wait = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) Thread.sleep(wait)

            val connection = URL(
                "https://www.googleapis.com/books/v1/volumes?q=$encoded&maxResults=10"
            ).openConnection() as HttpURLConnection
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            try {
                val code = connection.responseCode
                lastRequestAt = System.currentTimeMillis()
                if (code == 429 && attempt < MAX_RETRIES) {
                    val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
                    val backoff = retryAfter?.let { it * 1000L }
                        ?: (2000L shl attempt)
                    attempt++
                    Thread.sleep(backoff.coerceIn(1000L, 30000L))
                    continue
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw MetadataSearchException(code, "Google Books: HTTP $code")
                break responseBody
            } finally {
                connection.disconnect()
            }
        }
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
                val coverUrl = info.optJSONObject("imageLinks")?.optString("thumbnail")
                    ?.takeUnless { it.isNullOrBlank() || it == "null" }
                    ?.replace("http://", "https://")?.replace("&amp;", "&").orEmpty()

                // Series fields are inconsistently exposed by catalog APIs.
                val seriesInfo = info.optJSONObject("seriesInfo")
                    ?: items.optJSONObject(index)?.optJSONObject("seriesInfo")
                var series = seriesInfo?.optString("name").orEmpty().takeUnless { it == "null" }.orEmpty()
                if (series.isBlank()) series = info.optString("series").takeUnless { it == "null" }.orEmpty()
                var seriesNumber = listOf("bookDisplayNumber", "volumeNumber", "seriesNumber", "seriesIndex")
                    .firstNotNullOfOrNull { key ->
                        seriesInfo?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
                            ?: info.optString(key).takeIf { it.isNotBlank() && it != "null" }
                    }.orEmpty()
                if (series.isBlank()) {
                    val seriesMatch = Regex("""(?:сер(?:ія|ии)|series)\s*[:—-]\s*([^\[\]()]+?)(?:\s*[,;:]?\s*(?:кн\.?|книга|book|том|vol\.?|volume)\s*#?\s*(\d+(?:[.,]\d+)?))?$""", RegexOption.IGNORE_CASE)
                        .find(listOf(title, info.optString("subtitle"), info.optString("description")).joinToString(" "))
                    if (seriesMatch != null) {
                        series = seriesMatch.groupValues[1].trim()
                        if (seriesNumber.isBlank()) seriesNumber = seriesMatch.groupValues[2]
                    }
                }

                BookMeta(
                    title = title,
                    author = authors,
                    description = info.optString("description").takeUnless { it == "null" }.orEmpty(),
                    publisher = info.optString("publisher").takeUnless { it == "null" }.orEmpty(),
                    year = Regex("\\d{4}").find(published)?.value.orEmpty(),
                    isbn = foundIsbn,
                    language = info.optString("language").takeUnless { it == "null" }.orEmpty(),
                    subjects = subjects,
                    series = series,
                    seriesNumber = seriesNumber,
                    coverUrl = coverUrl
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}
