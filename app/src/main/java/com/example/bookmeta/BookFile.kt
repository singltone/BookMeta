
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
    var coverUri: String = "",
    var coverData: ByteArray? = null
)

object BookFile {

    fun read(resolver: ContentResolver, uri: Uri): BookMeta {
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не вдалося відкрити файл")

        return when {
            name.endsWith(".fb2") -> readFb2(bytes)
            name.endsWith(".epub") -> readEpub(bytes)
            else -> error("Підтримуються лише файли EPUB і FB2")
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, meta: BookMeta) {
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val input = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не вдалося відкрити книгу")

        val coverBytes = meta.coverUri.takeIf { it.isNotBlank() }?.let {
            resolver.openInputStream(Uri.parse(it))?.use { stream -> stream.readBytes() }
        } ?: meta.coverData

        val output = when {
            name.endsWith(".fb2") -> writeFb2(input, meta, coverBytes)
            name.endsWith(".epub") -> writeEpub(input, meta, coverBytes)
            else -> error("Підтримуються лише файли EPUB і FB2")
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
        // Resolve the FB2 cover reference to its embedded base64 binary.
        val source = bytes.toString(Charsets.UTF_8)
        val coverHref = Regex("""<coverpage\b[^>]*>.*?<image\b[^>]*(?:xlink:href|href)=["']#([^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
            .find(source)?.groupValues?.get(1)
        if (!coverHref.isNullOrBlank()) {
            val binary = Regex("""<binary\b(?=[^>]*\bid=["']${Regex.escape(coverHref)}["'])[^>]*>(.*?)</binary>""", RegexOption.DOT_MATCHES_ALL)
                .find(source)?.groupValues?.get(1)?.filterNot { it.isWhitespace() }
            if (!binary.isNullOrBlank()) {
                meta.coverData = runCatching { Base64.getMimeDecoder().decode(binary) }.getOrNull()
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
                "(<$tag\\b[^>]*>).*?(</$tag>)",
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

        // Update the first FB2 author while preserving the original XML structure.
        if (meta.author.isNotBlank()) {
            val parts = meta.author.trim().split(Regex("\\s+"), limit = 3)
            val first = parts.getOrElse(0) { "" }
            val middle = if (parts.size > 2) parts[1] else ""
            val last = if (parts.size > 2) parts[2] else parts.getOrElse(1) { "" }
            val authorRegex = Regex("""<author\b[^>]*>.*?</author>""", RegexOption.DOT_MATCHES_ALL)
            val authorMatch = authorRegex.find(xml)
            if (authorMatch != null) {
                var authorXml = authorMatch.value
                fun setAuthorPart(tag: String, value: String) {
                    if (value.isBlank()) return
                    val partRegex = Regex("""(<$tag\b[^>]*>).*?(</$tag>)""", RegexOption.DOT_MATCHES_ALL)
                    val partMatch = partRegex.find(authorXml)
                    if (partMatch != null) {
                        authorXml = authorXml.replaceRange(
                            partMatch.range, partMatch.groupValues[1] + xml(value) + partMatch.groupValues[2]
                        )
                    } else {
                        authorXml = authorXml.replace("</author>", "<$tag>${xml(value)}</$tag></author>")
                    }
                }
                setAuthorPart("first-name", first)
                setAuthorPart("middle-name", middle)
                setAuthorPart("last-name", last)
                xml = xml.replaceRange(authorMatch.range, authorXml)
            }
        }
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
        val identifiers = Regex(
            """<(?:dc:)?identifier\b([^>]*)>(.*?)</(?:dc:)?identifier>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(opf).toList()
        val isbnEntry = identifiers.firstOrNull {
            it.groupValues[1].contains("ISBN", true) ||
                it.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    .matches(Regex("(?:97[89][ -]?)?[0-9 -]{10,17}"))
        }
        meta.isbn = isbnEntry?.groupValues?.get(2)
            ?.replace(Regex("<[^>]+>"), "")?.let(::unescapeXml)?.trim().orEmpty()

        meta.author = Regex(
            """<(?:dc:)?creator\b[^>]*>(.*?)</(?:dc:)?creator>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(opf).map { unescapeXml(it.groupValues[1].replace(Regex("<[^>]+>"), "").trim()) }
            .filter { it.isNotBlank() }.joinToString(", ")

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
            """<meta\b[^>]*name=["']calibre:series_index["'][^>]*content=["']([^"']*)["'][^>]*/?>""",
            RegexOption.IGNORE_CASE
        ).find(opf)
        meta.seriesNumber = seriesNumber?.groupValues?.get(1).orEmpty()

        // EPUB cover can be declared by EPUB 2 metadata or EPUB 3 manifest properties.
        val coverId = Regex("""<meta\b[^>]*name=["']cover["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)
        val coverItem = if (!coverId.isNullOrBlank()) {
            Regex("""<item\b(?=[^>]*id=["']${Regex.escape(coverId)}["'])[^>]*href=["']([^"']+)["'][^>]*/?>""", RegexOption.IGNORE_CASE)
                .find(opf)
        } else null
        val epub3Cover = Regex("""<item\b(?=[^>]*properties=["'][^"']*cover-image)[^>]*href=["']([^"']+)["'][^>]*/?>""", RegexOption.IGNORE_CASE)
            .find(opf)
        val coverHref = coverItem?.groupValues?.get(1) ?: epub3Cover?.groupValues?.get(1)
        if (!coverHref.isNullOrBlank()) {
            val opfDir = opfPath.substringBeforeLast('/', "")
            val coverPath = normalizeZipPath(if (opfDir.isBlank()) coverHref else "$opfDir/$coverHref")
            meta.coverData = entries[coverPath]
        }

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
                Regex("""<meta\b[^>]*name=["']calibre:series["'][^>]*/?>""", RegexOption.IGNORE_CASE),
                ""
            )
            opf = opf.replace(
                Regex("""<meta\b[^>]*name=["']calibre:series_index["'][^>]*/?>""", RegexOption.IGNORE_CASE),
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
                    // Remove an older BookMeta cover entry before adding the replacement.
                    val oldItem = Regex("""<item\b(?=[^>]*id=["']bookmeta-cover["'])[^>]*/?>""", RegexOption.IGNORE_CASE)
                    opf = oldItem.replace(opf, "")
                    val refreshedManifestEnd = opf.indexOf("</manifest>", manifestStart.range.first)
                    if (refreshedManifestEnd >= 0) {
                        val item = """<item id="$id" href="$coverFile" media-type="$mediaType" properties="cover-image"/>"""
                        opf = opf.substring(0, refreshedManifestEnd) + item + opf.substring(refreshedManifestEnd)
                    }
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
            // EPUB requires the mimetype entry to be first and stored without compression.
            val mimetype = entries["mimetype"]
            if (mimetype != null) {
                val entry = ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = java.util.zip.CRC32().apply { update(mimetype) }.value
                }
                zip.putNextEntry(entry)
                zip.write(mimetype)
                zip.closeEntry()
            }

            entries.forEach { (name, bytes) ->
                if (name != "mimetype") {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun normalizeZipPath(path: String): String {
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
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
