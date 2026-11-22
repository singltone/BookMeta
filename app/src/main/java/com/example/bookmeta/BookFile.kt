
package com.example.bookmeta

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
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
    var coverData: ByteArray? = null,
    var coverUrl: String = "",
    var removeCover: Boolean = false
)

object BookFile {

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        val fromProvider = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
    }

    fun read(resolver: ContentResolver, uri: Uri): BookMeta {
        val name = displayName(resolver, uri).lowercase()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не вдалося відкрити файл")

        return when {
            name.endsWith(".fb2") -> readFb2(bytes)
            name.endsWith(".epub") -> readEpub(bytes)
            else -> error("Підтримуються лише файли EPUB і FB2")
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, meta: BookMeta) {
        val name = displayName(resolver, uri).lowercase()
        val input = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не вдалося відкрити книгу")

        val selectedUri = meta.coverUri.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        val selectedCover = selectedUri?.let {
            CoverManager.readAsJpegBytes(resolver, Uri.parse(it))
                ?: error("Не вдалося прочитати вибрану обкладинку")
        }
        val coverBytes = if (meta.removeCover) null else selectedCover
            ?: meta.coverData?.let { CoverManager.normalizeAsJpeg(it) }

        val isFb2 = name.endsWith(".fb2")
        val output = when {
            isFb2 -> writeFb2(input, meta, coverBytes)
            name.endsWith(".epub") -> writeEpub(input, meta, coverBytes)
            else -> error("Підтримуються лише файли EPUB і FB2")
        }

        // Validate generated XML/archive before touching the original document.
        val check = if (isFb2) readFb2(output) else readEpub(output)
        fun comparable(value: String) = value.trim().replace(Regex("\\s+"), " ")
        val mismatches = listOf(
            "назва" to (meta.title to check.title),
            "автор" to (meta.author to check.author),
            "ISBN" to (meta.isbn to check.isbn),
            "видавництво" to (meta.publisher to check.publisher),
            "рік" to (meta.year to check.year),
            "мова" to (meta.language to check.language),
            "жанри" to (meta.subjects to check.subjects),
            "серія" to (meta.series to check.series),
            "номер серії" to (meta.seriesNumber to check.seriesNumber),
            "анотація" to (meta.description to check.description)
        ).filter { (_, pair) -> comparable(pair.first) != comparable(pair.second) }
            .map { it.first }
        if (mismatches.isNotEmpty()) {
            error("Перевірка перед записом не пройдена. Не збігаються поля: ${mismatches.joinToString()}")
        }
        if (meta.removeCover && check.coverData != null) {
            error("Перевірка перед записом не пройдена: обкладинка залишилася у файлі")
        }
        if (!meta.removeCover && coverBytes != null && check.coverData == null) {
            error("Перевірка перед записом не пройдена: обкладинку не вдалося вбудувати")
        }

        val outputStream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: error("Не вдалося записати файл")
        outputStream.use {
            it.write(output)
            it.flush()
        }
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
                            "annotation" -> meta.description = listOf(meta.description, value).filter { it.isNotBlank() }.joinToString(" ")
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
        val declaration = bytes.copyOfRange(0, minOf(bytes.size, 512)).toString(Charsets.US_ASCII)
        val encodingName = Regex("""encoding=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(declaration)?.groupValues?.get(1)
        val sourceCharset = encodingName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        var xml = bytes.toString(sourceCharset)
        fun replaceElement(source: String, tag: String, value: String, parent: String): String {
            val regex = Regex("<$tag\\b[^>]*>.*?</$tag>", RegexOption.DOT_MATCHES_ALL)
            val first = regex.find(source)
            val opening = first?.let { Regex("^<$tag\\b[^>]*>").find(it.value)?.value } ?: "<$tag>"
            val cleaned = regex.replace(source, "")
            if (value.isBlank()) return cleaned
            val node = "$opening${xml(value)}</$tag>"
            return if (cleaned.contains("</$parent>")) cleaned.replaceFirst("</$parent>", "$node</$parent>") else source
        }
        val titleMatch = Regex("<title-info\\b[^>]*>.*?</title-info>", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?: error("FB2: блок title-info не знайдено")
        var titleInfo = titleMatch.value
        titleInfo = replaceElement(titleInfo, "book-title", meta.title, "title-info")
        titleInfo = replaceElement(titleInfo, "annotation", meta.description, "title-info")
        titleInfo = replaceElement(titleInfo, "lang", meta.language, "title-info")

        val authorNodes = meta.author.split(Regex("\\s*,\\s*")).map { it.trim() }.filter { it.isNotBlank() }
            .joinToString("") { name ->
                val parts = name.split(Regex("\\s+"), limit = 3)
                val first = parts.getOrElse(0) { "" }
                val middle = if (parts.size > 2) parts[1] else ""
                val last = if (parts.size > 2) parts[2] else parts.getOrElse(1) { "" }
                "<author><first-name>${xml(first)}</first-name>" +
                    (if (middle.isBlank()) "" else "<middle-name>${xml(middle)}</middle-name>") +
                    (if (last.isBlank()) "" else "<last-name>${xml(last)}</last-name>") + "</author>"
            }
        val authorRegex = Regex("<author\\b[^>]*>.*?</author>", RegexOption.DOT_MATCHES_ALL)
        titleInfo = if (authorRegex.containsMatchIn(titleInfo)) authorRegex.replace(titleInfo, authorNodes)
            else if (authorNodes.isNotBlank()) titleInfo.replace("</title-info>", "$authorNodes</title-info>")
            else titleInfo

        titleInfo = Regex("<genre\\b[^>]*>.*?</genre>", RegexOption.DOT_MATCHES_ALL).replace(titleInfo, "")
        if (meta.subjects.isNotBlank()) {
            val genres = meta.subjects.split(",").map { it.trim() }.filter { it.isNotBlank() }
                .joinToString("") { "<genre>${xml(it)}</genre>" }
            titleInfo = titleInfo.replace("</title-info>", "$genres</title-info>")
        }
        titleInfo = Regex("<sequence\\b[^>]*/>|<sequence\\b[^>]*>.*?</sequence>", RegexOption.DOT_MATCHES_ALL)
            .replace(titleInfo, "")
        if (meta.series.isNotBlank()) {
            val number = meta.seriesNumber.ifBlank { "1" }
            val sequence = "<sequence name=\"${xml(meta.series)}\" number=\"${xml(number)}\"/>"
            titleInfo = titleInfo.replace("</title-info>", "$sequence</title-info>")
        }
        xml = xml.replaceRange(titleMatch.range, titleInfo)

        if ((meta.publisher.isNotBlank() || meta.year.isNotBlank() || meta.isbn.isNotBlank()) &&
            !Regex("<publish-info\\b").containsMatchIn(xml)) {
            xml = xml.replaceFirst("</description>", "<publish-info></publish-info></description>")
        }
        val publishMatch = Regex("<publish-info\\b[^>]*>.*?</publish-info>", RegexOption.DOT_MATCHES_ALL).find(xml)
        if (publishMatch != null) {
            var publishInfo = publishMatch.value
            publishInfo = replaceElement(publishInfo, "publisher", meta.publisher, "publish-info")
            publishInfo = replaceElement(publishInfo, "year", meta.year, "publish-info")
            publishInfo = replaceElement(publishInfo, "isbn", meta.isbn, "publish-info")
            xml = xml.replaceRange(publishMatch.range, publishInfo)
        }

        if (meta.removeCover) {
            val oldCoverId = Regex("""<coverpage\b[^>]*>.*?<image\b[^>]*(?:xlink:href|href)=["']#([^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)
            xml = Regex("<coverpage\\b[^>]*>.*?</coverpage>", RegexOption.DOT_MATCHES_ALL).replace(xml, "")
            if (!oldCoverId.isNullOrBlank()) {
                val binaryRegex = Regex("""<binary\b(?=[^>]*\bid=["']${Regex.escape(oldCoverId)}["'])[^>]*>.*?</binary>""", RegexOption.DOT_MATCHES_ALL)
                xml = binaryRegex.replace(xml, "")
            }
            xml = Regex("""<binary\b(?=[^>]*\bid=["']bookmeta_cover["'])[^>]*>.*?</binary>""", RegexOption.DOT_MATCHES_ALL).replace(xml, "")
        } else if (coverBytes != null) {
            val previousCoverId = Regex("""<coverpage\b[^>]*>.*?<image\b[^>]*(?:xlink:href|href)=["']#([^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)
            val id = "bookmeta_cover"
            if (!previousCoverId.isNullOrBlank() && previousCoverId != id) {
                val oldBinary = Regex("""<binary\b(?=[^>]*\bid=["']${Regex.escape(previousCoverId)}["'])[^>]*>.*?</binary>""", RegexOption.DOT_MATCHES_ALL)
                xml = oldBinary.replace(xml, "")
            }
            val encoded = Base64.getEncoder().encodeToString(coverBytes)
            val binary = """<binary id="$id" content-type="image/jpeg">$encoded</binary>"""
            val coverpage = """<coverpage><image xmlns:xlink="http://www.w3.org/1999/xlink" xlink:href="#$id"/></coverpage>"""
            val coverRegex = Regex("<coverpage\\b[^>]*>.*?</coverpage>", RegexOption.DOT_MATCHES_ALL)
            xml = if (coverRegex.containsMatchIn(xml)) coverRegex.replaceFirst(xml, coverpage)
                else xml.replaceFirst("</title-info>", "$coverpage</title-info>")
            xml = Regex("""<binary\b(?=[^>]*\bid=["']$id["'])[^>]*>.*?</binary>""", RegexOption.DOT_MATCHES_ALL).replace(xml, "")
            xml = xml.replace("</FictionBook>", "$binary</FictionBook>")
        }
        return xml.toByteArray(sourceCharset)
    }

    // ---------------- EPUB ----------------

    private fun readEpub(bytes: ByteArray): BookMeta {
        val entries = unzip(bytes)
        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8).orEmpty()
        val declaredPath = Regex("""<rootfile\b[^>]*full-path=["']([^"']+\.opf)["']""", RegexOption.IGNORE_CASE)
            .find(container)?.groupValues?.get(1)
        val opfPath = declaredPath?.takeIf { entries.containsKey(it) }
            ?: entries.keys.firstOrNull { it.endsWith(".opf", true) }
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
        if (meta.series.isBlank()) {
            val collection = Regex("""<meta\b(?=[^>]*property=["']belongs-to-collection["'])[^>]*>(.*?)</meta>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(opf)
            meta.series = collection?.groupValues?.get(1)?.let(::unescapeXml)?.trim().orEmpty()
            val position = Regex("""<meta\b(?=[^>]*property=["']group-position["'])[^>]*>(.*?)</meta>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(opf)
            if (meta.seriesNumber.isBlank()) meta.seriesNumber = position?.groupValues?.get(1)?.trim().orEmpty()
        }

        // EPUB cover can be declared by EPUB 2 metadata or EPUB 3 manifest properties.
        fun attribute(tag: String, name: String): String? =
            Regex("""\b${Regex.escape(name)}=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1)
        val metaTags = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf)
        val coverMeta = metaTags.firstOrNull { attribute(it.value, "name").equals("cover", true) }
        val coverId = coverMeta?.value?.let { attribute(it, "content") }
        val items = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).map { it.value }.toList()
        val coverItem = items.firstOrNull { !coverId.isNullOrBlank() && attribute(it, "id") == coverId }
        val epub3Cover = items.firstOrNull { attribute(it, "properties")?.split(Regex("\\s+"))?.contains("cover-image") == true }
        val coverHref = coverItem?.let { attribute(it, "href") } ?: epub3Cover?.let { attribute(it, "href") }
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
        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8).orEmpty()
        val declaredPath = Regex("""<rootfile\b[^>]*full-path=["']([^"']+\.opf)["']""", RegexOption.IGNORE_CASE)
            .find(container)?.groupValues?.get(1)
        val opfPath = declaredPath?.takeIf { entries.containsKey(it) }
            ?: entries.keys.firstOrNull { it.endsWith(".opf", true) }
            ?: error("EPUB: OPF-файл не знайдено")
        var opf = entries[opfPath]!!.toString(Charsets.UTF_8)

        fun setDc(tag: String, value: String) {
            val regex = Regex("<(?:dc:)?$tag\\b[^>]*>.*?</(?:dc:)?$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val first = regex.find(opf)
            val opening = first?.let { Regex("^<[^>]+>").find(it.value)?.value } ?: "<dc:$tag>"
            val closing = first?.let { Regex("</[^>]+>$").find(it.value)?.value } ?: "</dc:$tag>"
            opf = regex.replace(opf, "")
            if (value.isNotBlank()) {
                val node = "$opening${xml(value)}$closing"
                opf = opf.replace("</metadata>", "$node</metadata>")
            }
        }
        fun setDcList(tag: String, values: List<String>) {
            val regex = Regex("<(?:dc:)?$tag\\b[^>]*>.*?</(?:dc:)?$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            opf = regex.replace(opf, "")
            val nodes = values.map { it.trim() }.filter { it.isNotBlank() }
                .joinToString("") { "<dc:$tag>${xml(it)}</dc:$tag>" }
            if (nodes.isNotBlank()) opf = opf.replace("</metadata>", "$nodes</metadata>")
        }
        setDc("title", meta.title)
        setDcList("creator", meta.author.split(Regex("\\s*,\\s*")))
        setDc("description", meta.description)
        setDc("publisher", meta.publisher)
        setDc("date", meta.year)
        setDc("language", meta.language)

        val identifiers = Regex("<(?:dc:)?identifier\\b[^>]*>.*?</(?:dc:)?identifier>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        fun isIsbnIdentifier(value: String): Boolean =
            value.contains("ISBN", true) ||
                Regex("(?:97[89][ -]?)?[0-9][0-9 -]{8,}[0-9Xx]").containsMatchIn(value.replace(Regex("<[^>]+>"), ""))
        val isbnMatch = identifiers.findAll(opf).firstOrNull { isIsbnIdentifier(it.value) }
        val isbnOpening = isbnMatch?.let { Regex("^<[^>]+>").find(it.value)?.value } ?: "<dc:identifier id=\"bookmeta-isbn\">"
        val isbnClosing = isbnMatch?.let { Regex("</[^>]+>$").find(it.value)?.value } ?: "</dc:identifier>"
        opf = identifiers.replace(opf) { match -> if (isIsbnIdentifier(match.value)) "" else match.value }
        if (meta.isbn.isNotBlank()) {
            val node = "$isbnOpening${xml(meta.isbn)}$isbnClosing"
            opf = opf.replace("</metadata>", "$node</metadata>")
        }

        opf = Regex("<dc:subject\\b[^>]*>.*?</dc:subject>", RegexOption.DOT_MATCHES_ALL).replace(opf, "")
        val subjects = meta.subjects.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .joinToString("") { "<dc:subject>${xml(it)}</dc:subject>" }
        if (subjects.isNotBlank()) opf = opf.replace("</metadata>", "$subjects</metadata>")

        val metaTags = Regex("<meta\\b[^>]*(?:/>|>.*?</meta>)", RegexOption.DOT_MATCHES_ALL)
        opf = metaTags.replace(opf) { match ->
            val tag = match.value
            val seriesTag = Regex("""name=["']calibre:series(?:_index)?["']""", RegexOption.IGNORE_CASE).containsMatchIn(tag)
            val collectionTag = Regex("""property=["']belongs-to-collection["']""", RegexOption.IGNORE_CASE).containsMatchIn(tag)
            val refinementTag = Regex("""refines=["']#bookmeta-series["']""", RegexOption.IGNORE_CASE).containsMatchIn(tag)
            if (seriesTag || collectionTag || refinementTag) "" else tag
        }
        if (meta.series.isNotBlank()) {
            val number = meta.seriesNumber.ifBlank { "1" }
            val seriesXml = "<meta name=\"calibre:series\" content=\"${xml(meta.series)}\"/>" +
                "<meta name=\"calibre:series_index\" content=\"${xml(number)}\"/>" +
                "<meta property=\"belongs-to-collection\" id=\"bookmeta-series\">${xml(meta.series)}</meta>" +
                "<meta refines=\"#bookmeta-series\" property=\"collection-type\">series</meta>" +
                "<meta refines=\"#bookmeta-series\" property=\"group-position\">${xml(number)}</meta>"
            opf = opf.replace("</metadata>", "$seriesXml</metadata>")
        }

        val opfDir = opfPath.substringBeforeLast('/', "")
        val coverFile = "bookmeta-cover.jpg"
        val coverPath = if (opfDir.isBlank()) coverFile else "$opfDir/$coverFile"
        val coverId = "bookmeta-cover"
        if (meta.removeCover) {
            val coverIds = Regex("""<meta\b[^>]*name=["']cover["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(opf).map { it.groupValues[1] }.toSet()
            opf = Regex("""<meta\b[^>]*name=["']cover["'][^>]*/?>""", RegexOption.IGNORE_CASE).replace(opf, "")
            val removedPaths = mutableListOf<String>()
            opf = Regex("<item\\b[^>]*/?>", RegexOption.IGNORE_CASE).replace(opf) { match ->
                val item = match.value
                val id = Regex("""\bid=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1).orEmpty()
                val href = Regex("""\bhref=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1).orEmpty()
                val coverItem = id == coverId || id in coverIds ||
                    Regex("""properties=["'][^"']*cover-image""", RegexOption.IGNORE_CASE).containsMatchIn(item)
                if (coverItem) {
                    if (href.isNotBlank()) removedPaths.add(normalizeZipPath(if (opfDir.isBlank()) href else "$opfDir/$href"))
                    ""
                } else item
            }
            removedPaths.forEach { entries.remove(it) }
            entries.remove(coverPath)
        } else if (coverBytes != null) {
            entries[coverPath] = coverBytes
            opf = Regex("""<item\b(?=[^>]*id=["']$coverId["'])[^>]*/?>""", RegexOption.IGNORE_CASE).replace(opf, "")
            val manifestClose = opf.indexOf("</manifest>")
            if (manifestClose < 0) error("EPUB: блок manifest не знайдено")
            val item = """<item id="$coverId" href="$coverFile" media-type="image/jpeg" properties="cover-image"/>"""
            opf = opf.substring(0, manifestClose) + item + opf.substring(manifestClose)
            opf = Regex("""<meta\b[^>]*name=["']cover["'][^>]*/?>""", RegexOption.IGNORE_CASE).replace(opf, "")
            opf = opf.replace("</metadata>", """<meta name="cover" content="$coverId"/></metadata>""")
        }
        entries[opfPath] = opf.toByteArray(Charsets.UTF_8)
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
