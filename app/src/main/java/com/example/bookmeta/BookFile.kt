
package com.example.bookmeta

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BookMeta(
    var title: String = "",
    var author: String = "",
    var description: String = "",
    var publisher: String = "",
    var year: String = "",
    var isbn: String = "",
    var language: String = "",
    var subjects: String = "",
    var series: String = "",
    var seriesNumber: String = "",
    var coverUri: String = ""
)

object BookFile {

    fun read(resolver: ContentResolver, uri: Uri): BookMeta {
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не вдалося відкрити файл")

        return when {
            name.endsWith(".fb2") -> readFb2(bytes)
            else -> readEpub(bytes)
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, meta: BookMeta) {
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val input = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не вдалося відкрити книгу")

        val coverBytes = meta.coverUri.takeIf { it.isNotBlank() }?.let {
            resolver.openInputStream(Uri.parse(it))?.use { stream -> stream.readBytes() }
        }

        val output = when {
            name.endsWith(".fb2") -> writeFb2(input, meta, coverBytes)
            else -> writeEpub(input, meta, coverBytes)
        }

        resolver.openOutputStream(uri, "wt")?.use {
            it.write(output)
            it.flush()
        } ?: error("Не вдалося записати файл")
    }

    // ---------------- FB2 ----------------

    private fun readFb2(bytes: ByteArray): BookMeta {
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), null)

        val meta = BookMeta()
        var tag = ""
        var inTitleInfo = false
        var inAuthor = false
        var inSequence = false
        var authorParts = mutableListOf<String>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name

                    when (tag) {
                        "title-info" -> inTitleInfo = true
                        "author" -> {
                            inAuthor = true
                            authorParts = mutableListOf()
                        }
                        "sequence" -> if (inTitleInfo) {
                            inSequence = true
                            meta.series = parser.getAttributeValue(null, "name") ?: ""
                            meta.seriesNumber = parser.getAttributeValue(null, "number") ?: ""
                        }
                        "coverpage" -> {
                            val image = parser.nextTag()
                            if (image == XmlPullParser.START_TAG && parser.name == "image") {
                                meta.coverUri = parser.getAttributeValue(
                                    "http://www.w3.org/1999/xlink", "href"
                                ) ?: parser.getAttributeValue(null, "href") ?: ""
                            }
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val value = parser.text?.trim().orEmpty()
                    if (value.isNotBlank()) {
                        when (tag) {
                            "book-title" -> meta.title = value
                            "annotation" -> meta.description += value
                            "lang" -> meta.language = value
                            "genre" -> meta.subjects = append(meta.subjects, value)
                            "first-name", "middle-name", "last-name" ->
                                if (inAuthor) authorParts.add(value)
                            "isbn" -> meta.isbn = value
                            "publisher" -> meta.publisher = value
                            "year" -> meta.year = value
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "author" -> {
                            if (authorParts.isNotEmpty()) {
                                meta.author = append(meta.author, authorParts.joinToString(" "))
                            }
                            inAuthor = false
                        }
                        "title-info" -> inTitleInfo = false
                        "sequence" -> inSequence = false
                    }
                    tag = ""
                }
            }
        }
        return meta
    }

    private fun writeFb2(
        bytes: ByteArray,
        meta: BookMeta,
        coverBytes: ByteArray?
    ): ByteArray {
        var xml = bytes.toString(Charsets.UTF_8)

        fun replaceText(tag: String, value: String) {
            if (value.isBlank()) return
            val escaped = xml(value)
            val regex = Regex(
                "(<$tag\\\\b[^>]*>).*?(</$tag>)",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = regex.find(xml)
            if (match != null) {
                xml = xml.replaceRange(
                    match.range,
                    match.groupValues[1] + escaped + match.groupValues[2]
                )
            }
        }

        replaceText("book-title", meta.title)
        replaceText("annotation", meta.description)
        replaceText("lang", meta.language)
        replaceText("isbn", meta.isbn)
        replaceText("publisher", meta.publisher)
        replaceText("year", meta.year)

        // Genre: append only if provided
        if (meta.subjects.isNotBlank() &&
            !xml.contains(meta.subjects, ignoreCase = true)
        ) {
            val genres = meta.subjects.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val genreXml = genres.joinToString("") { "<genre>${xml(it)}</genre>" }
            xml = xml.replaceFirst("</title-info>", "$genreXml</title-info>")
        }

        // Series
        if (meta.series.isNotBlank()) {
            val number = meta.seriesNumber.ifBlank { "1" }
            val sequence = """<sequence name="${xml(meta.series)}" number="${xml(number)}"/>"""
            val regex = Regex("""<sequence\b[^>]*/>""")
            xml = if (regex.containsMatchIn(xml)) {
                regex.replaceFirst(xml, sequence)
            } else {
                xml.replaceFirst("</title-info>", "$sequence</title-info>")
            }
        }

        // Cover: FB2 uses <binary> plus <coverpage><image href="#id"/>
        if (coverBytes != null) {
            val extension = "jpg"
            val id = "bookmeta_cover"
            val encoded = Base64.getEncoder().encodeToString(coverBytes)
            val binary = """<binary id="$id" content-type="image/$extension">$encoded</binary>"""

            val coverpage = """<coverpage><image href="#$id" xmlns:xlink="http://www.w3.org/1999/xlink" xlink:href="#$id"/></coverpage>"""

            val coverRegex = Regex("""<coverpage\b.*?</coverpage>""", RegexOption.DOT_MATCHES_ALL)
            xml = if (coverRegex.containsMatchIn(xml)) {
                coverRegex.replaceFirst(xml, coverpage)
            } else {
                xml.replaceFirst("</title-info>", "$coverpage</title-info>")
            }

            val binaryRegex = Regex("""<binary\b[^>]*id=["']$id["'][^>]*>.*?</binary>""", RegexOption.DOT_MATCHES_ALL)
            xml = binaryRegex.replace(xml, "")

            xml = xml.replace("</FictionBook>", "$binary</FictionBook>")
        }

        return xml.toByteArray(Charsets.UTF_8)
    }

    // ---------------- EPUB ----------------

    private fun readEpub(bytes: ByteArray): BookMeta {
        val entries = unzip(bytes)
        val opfPath = entries.keys.firstOrNull { it.endsWith(".opf", true) }
            ?: return BookMeta()

        val opf = entries[opfPath]?.toString(Charsets.UTF_8) ?: return BookMeta()
        val meta = BookMeta()

        fun dc(tag: String): String {
            val regex = Regex(
                """<(?:dc:)?$tag\b[^>]*>(.*?)</(?:dc:)?$tag>""",
                RegexOption.DOT_MATCHES_ALL
            )
            return regex.find(opf)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.let(::unescapeXml)
                ?.trim().orEmpty()
        }

        meta.title = dc("title")
        meta.author = dc("creator")
        meta.description = dc("description")
        meta.publisher = dc("publisher")
        meta.year = dc("date")
        meta.language = dc("language")
        meta.isbn = Regex("""identifier[^>]*>.*?ISBN.*?""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.getOrNull(0)?.let {
                Regex(""">(.*?)<""").find(it)?.groupValues?.get(1)
            }.orEmpty()

        val subjects = Regex(
            """<dc:subject\b[^>]*>(.*?)</dc:subject>""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(opf).map { unescapeXml(it.groupValues[1].trim()) }
            .filter { it.isNotBlank() }.toList()
        meta.subjects = subjects.joinToString(", ")

        val series = Regex(
            """<meta\b[^>]*name=["']calibre:series["'][^>]*content=["']([^"']*)["'][^>]*/?>""",
            RegexOption.IGNORE_CASE
        ).find(opf)
        meta.series = series?.groupValues?.get(1)?.let(::unescapeXml).orEmpty()

        val seriesNumber = Regex(
            """<meta\b[^>]*name=[""]calibre:series_index[""][^>]*content=[""]([^""]*)[""][^>]*/?>""",
            RegexOption.IGNORE_CASE
        ).find(opf)
        meta.seriesNumber = seriesNumber?.groupValues?.get(1).orEmpty()

        return meta
    }

    private fun writeEpub(
        bytes: ByteArray,
        meta: BookMeta,
        coverBytes: ByteArray?
    ): ByteArray {
        val entries = unzip(bytes)
        val opfPath = entries.keys.firstOrNull { it.endsWith(".opf", true) }
            ?: error("EPUB: OPF-файл не знайдено")

        var opf = entries[opfPath]!!.toString(Charsets.UTF_8)

        fun setDc(tag: String, value: String) {
            if (value.isBlank()) return
            val escaped = xml(value)
            val regex = Regex(
                """(<dc:$tag\b[^>]*>).*?(</dc:$tag>)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = regex.find(opf)
            opf = if (match != null) {
                opf.replaceRange(
                    match.range,
                    match.groupValues[1] + escaped + match.groupValues[2]
                )
            } else {
                opf.replace("</metadata>", "<dc:$tag>$escaped</dc:$tag></metadata>")
            }
        }

        setDc("title", meta.title)
        setDc("creator", meta.author)
        setDc("description", meta.description)
        setDc("publisher", meta.publisher)
        setDc("date", meta.year)
        setDc("language", meta.language)

        // ISBN
        if (meta.isbn.isNotBlank()) {
            val escaped = xml(meta.isbn)
            val identifier = Regex("""<dc:identifier\b[^>]*>.*?</dc:identifier>""", RegexOption.DOT_MATCHES_ALL)
            val match = identifier.find(opf)
            if (match != null && match.value.contains("ISBN", true)) {
                opf = opf.replaceRange(match.range, match.value.replace(Regex(">.*?<"), ">$escaped<"))
            } else {
                opf = opf.replace("</metadata>", "<dc:identifier id=\"bookmeta-isbn\">$escaped</dc:identifier></metadata>")
            }
        }

        // Subjects/genres
        if (meta.subjects.isNotBlank()) {
            val existing = Regex("""<dc:subject\b[^>]*>.*?</dc:subject>""", RegexOption.DOT_MATCHES_ALL)
            opf = existing.replace(opf, "")
            val subjects = meta.subjects.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val subjectXml = subjects.joinToString("") { "<dc:subject>${xml(it)}</dc:subject>" }
            opf = opf.replace("</metadata>", "$subjectXml</metadata>")
        }

        // Series metadata: calibre-compatible + EPUB3 collection metadata
        if (meta.series.isNotBlank()) {
            val seriesXml = """
                <meta name="calibre:series" content="${xml(meta.series)}"/>
                <meta name="calibre:series_index" content="${xml(meta.seriesNumber)}"/>
                <meta property="belongs-to-collection" id="bookmeta-series">${xml(meta.series)}</meta>
                <meta refines="#bookmeta-series" property="collection-type">series</meta>
                <meta refines="#bookmeta-series" property="group-position">${xml(meta.seriesNumber)}</meta>
            """.trimIndent()

            opf = opf.replace(
                Regex("""<meta\b[^>]*name=[""]calibre:series[""][^>]*/?>""", RegexOption.IGNORE_CASE),
                ""
            )
            opf = opf.replace(
                Regex("""<meta\b[^>]*name=[""]calibre:series_index[""][^>]*/?>""", RegexOption.IGNORE_CASE),
                ""
            )
            opf = opf.replace("</metadata>", "$seriesXml</metadata>")
        }

        // Cover image: embed image into EPUB and update manifest metadata
        if (coverBytes != null) {
            val opfDir = opfPath.substringBeforeLast('/', "")
            val coverFile = "bookmeta-cover.jpg"
            val coverPath = if (opfDir.isBlank()) coverFile else "$opfDir/$coverFile"
            entries[coverPath] = coverBytes

            val id = "bookmeta-cover"
            val mediaType = "image/jpeg"

            val manifestRegex = Regex("""<manifest\b[^>]*>""")
            val manifestStart = manifestRegex.find(opf)
            if (manifestStart != null) {
                val manifestEnd = opf.indexOf("</manifest>", manifestStart.range.last)
                if (manifestEnd >= 0) {
                    val item = """<item id="$id" href="$coverFile" media-type="$mediaType" properties="cover-image"/>"""
                    opf = opf.substring(0, manifestEnd) + item + opf.substring(manifestEnd)
                }
            }

            // OPF2 compatibility metadata
            opf = opf.replace("</metadata>", """<meta name="cover" content="$id"/></metadata>""")

            // OPF path may have nested folders: use relative cover path
            entries[opfPath] = opf.toByteArray(Charsets.UTF_8)
        } else {
            entries[opfPath] = opf.toByteArray(Charsets.UTF_8)
        }

        return zip(entries)
    }

    // ---------------- ZIP helpers ----------------

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val buffer = ByteArrayOutputStream()
                    zip.copyTo(buffer)
                    result[entry.name] = buffer.toByteArray()
                }
            }
        }
        return result
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    // ---------------- XML helpers ----------------

    private fun append(old: String, value: String): String =
        listOf(old, value).filter { it.isNotBlank() }.joinToString(", ")

    private fun xml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun unescapeXml(value: String): String =
        value.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
