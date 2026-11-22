package com.example.bookmeta

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

 data class BookMeta(
    var title: String = "", var author: String = "", var description: String = "",
    var publisher: String = "", var year: String = "", var isbn: String = "",
    var language: String = "", var subjects: String = "", var series: String = "",
    var seriesNumber: String = "", var coverUri: String = ""
)

object BookFile {
    fun read(resolver: ContentResolver, uri: Uri): BookMeta {
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open file")
        return if (name.endsWith(".fb2")) readFb2(bytes) else readEpub(bytes)
    }
    fun write(resolver: ContentResolver, uri: Uri, meta: BookMeta) {
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val input = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open file")
        val output = if (name.endsWith(".fb2")) writeFb2(input, meta) else writeEpub(input, meta)
        resolver.openOutputStream(uri, "wt")?.use { it.write(output) } ?: error("Cannot write file")
    }
    private fun readFb2(bytes: ByteArray): BookMeta {
        val p = Xml.newPullParser(); p.setInput(bytes.inputStream(), null)
        val m = BookMeta(); var tag = ""; var inTitle = false; var inAuthor = false
        val authorParts = mutableListOf<String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) { tag = p.name; if (tag == "title-info") inTitle = true; if (tag == "author") inAuthor = true }
            if (p.eventType == XmlPullParser.TEXT) {
                val t = p.text?.trim().orEmpty(); if (t.isNotEmpty()) when(tag) {
                    "book-title" -> m.title = t; "annotation" -> m.description += t; "lang" -> m.language = t
                    "first-name", "middle-name", "last-name" -> if (inAuthor) authorParts.add(t)
                    "genre" -> m.subjects = listOf(m.subjects,t).filter { it.isNotBlank() }.joinToString(", ")
                }
            }
            if (p.eventType == XmlPullParser.END_TAG) { if (p.name == "author") inAuthor = false; if (p.name == "title-info") inTitle = false; tag = "" }
        }
        m.author = authorParts.joinToString(" "); return m
    }
    private fun readEpub(bytes: ByteArray): BookMeta {
        val entries = unzip(bytes); val opfName = entries.keys.firstOrNull { it.endsWith(".opf", true) } ?: return BookMeta()
        val xml = entries[opfName]?.toString(Charsets.UTF_8) ?: return BookMeta()
        val m = BookMeta()
        fun value(tag: String) = Regex("<(?:dc:)?$tag\\b[^>]*>(.*?)</(?:dc:)?$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
        m.title=value("title"); m.author=value("creator"); m.description=value("description"); m.publisher=value("publisher"); m.year=value("date"); m.language=value("language"); m.isbn=Regex("isbn[^>]*>(.*?)</", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1).orEmpty()
        return m
    }
    private fun writeFb2(bytes: ByteArray, m: BookMeta): ByteArray {
        var s=bytes.toString(Charsets.UTF_8)
        fun replace(tag:String, value:String) { if(value.isBlank()) return; val escaped=xml(value); val r=Regex("(<$tag\\b[^>]*>).*?(</$tag>)", RegexOption.DOT_MATCHES_ALL); val match=r.find(s); if(match!=null) s=s.replaceRange(match.range, match.groupValues[1]+escaped+match.groupValues[2]) }
        replace("book-title",m.title); replace("annotation",m.description); replace("lang",m.language)
        return s.toByteArray(Charsets.UTF_8)
    }
    private fun writeEpub(bytes: ByteArray, m: BookMeta): ByteArray {
        val entries=unzip(bytes); val opf=entries.keys.firstOrNull { it.endsWith(".opf",true) } ?: error("EPUB OPF not found")
        var s=entries[opf]!!.toString(Charsets.UTF_8)
        fun set(tag:String, value:String) { if(value.isBlank())return; val escaped=xml(value); val r=Regex("(<dc:$tag\\b[^>]*>).*?(</dc:$tag>)",RegexOption.DOT_MATCHES_ALL); val match=r.find(s); if(match!=null)s=s.replaceRange(match.range,match.groupValues[1]+escaped+match.groupValues[2]) else s=s.replace("</metadata>","<dc:$tag>$escaped</dc:$tag></metadata>") }
        set("title",m.title); set("creator",m.author); set("description",m.description); set("publisher",m.publisher); set("date",m.year); set("language",m.language)
        entries[opf]=s.toByteArray(Charsets.UTF_8); return zip(entries)
    }
    private fun unzip(bytes:ByteArray):LinkedHashMap<String,ByteArray> { val out=linkedMapOf<String,ByteArray>(); ZipInputStream(bytes.inputStream()).use { z-> while(true){val e=z.nextEntry?:break;if(!e.isDirectory){val b=ByteArrayOutputStream();z.copyTo(b);out[e.name]=b.toByteArray()}}};return out }
    private fun zip(entries:Map<String,ByteArray>):ByteArray { val b=ByteArrayOutputStream(); ZipOutputStream(b).use { z-> entries.forEach{(n,v)->z.putNextEntry(ZipEntry(n));z.write(v);z.closeEntry()} };return b.toByteArray() }
    private fun xml(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
}
