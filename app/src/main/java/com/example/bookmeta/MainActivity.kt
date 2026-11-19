package com.example.bookmeta

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import javax.xml.parsers.DocumentBuilderFactory

data class BookMeta(
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val publisher: String = "",
    val year: String = "",
    val isbn: String = "",
    val language: String = "",
    val subjects: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BookMetaApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookMetaApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("Файл не вибрано") }
    var meta by remember { mutableStateOf(BookMeta()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Виберіть книгу або введіть назву для пошуку.") }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<BookMeta>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            fileName = uri.lastPathSegment ?: "Вибраний файл"
            scope.launch {
                busy = true
                try {
                    val read = withContext(Dispatchers.IO) { BookFile.read(context, uri) }
                    meta = read.first
                    status = read.second
                } catch (e: Exception) {
                    status = "Не вдалося прочитати файл: ${e.message}"
                } finally { busy = false }
            }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("BookMeta", style = MaterialTheme.typography.headlineSmall) },
                actions = { Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.padding(end = 16.dp)) })
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Метадані електронних книг", style = MaterialTheme.typography.titleLarge)
                Text("Пошук в інтернеті • редагування • збереження", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { picker.launch(arrayOf(
                    "application/epub+zip", "application/x-fictionbook+xml", "application/pdf",
                    "application/x-mobipocket-ebook", "application/octet-stream", "*/*"
                )) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Відкрити книгу")
                }
                Text(fileName, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Назва, автор або ISBN") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(enabled = query.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            results = withContext(Dispatchers.IO) { MetadataSearch.search(query) }
                            status = if (results.isEmpty()) "Нічого не знайдено." else "Знайдено варіантів: ${results.size}. Оберіть потрібний."
                        } catch (e: Exception) { status = "Помилка пошуку: ${e.message}" }
                        finally { busy = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Знайти метадані")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (results.isNotEmpty()) {
                    Text("Результати пошуку", style = MaterialTheme.typography.titleMedium)
                    results.forEach { item ->
                        ElevatedCard(onClick = { meta = item; status = "Метадані підставлено. Перевірте поля перед збереженням." }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.title.ifBlank { "Без назви" }, style = MaterialTheme.typography.titleMedium)
                                Text(item.author)
                                Text(listOf(item.year, item.publisher, item.isbn).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("Редагування", style = MaterialTheme.typography.titleLarge)
                MetaField("Назва", meta.title) { meta = meta.copy(title = it) }
                MetaField("Автор", meta.author) { meta = meta.copy(author = it) }
                MetaField("Анотація", meta.description, minLines = 3) { meta = meta.copy(description = it) }
                MetaField("Видавництво", meta.publisher) { meta = meta.copy(publisher = it) }
                MetaField("Рік видання", meta.year, keyboard = KeyboardType.Number) { meta = meta.copy(year = it) }
                MetaField("ISBN", meta.isbn) { meta = meta.copy(isbn = it) }
                MetaField("Мова", meta.language) { meta = meta.copy(language = it) }
                MetaField("Жанри / теги", meta.subjects) { meta = meta.copy(subjects = it) }
                Button(enabled = selectedUri != null && !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val msg = withContext(Dispatchers.IO) { BookFile.write(context, selectedUri!!, meta) }
                            status = msg
                        } catch (e: Exception) { status = "Помилка збереження: ${e.message}" }
                        finally { busy = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Зберегти метадані у файл")
                }
                Text("Підтримка запису в цій версії: EPUB, FB2. PDF/MOBI/AZW3/DJVU можна відкрити не завжди; їх запис потребує окремих форматних модулів. Перед редагуванням зробіть копію файлу.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MetaField(label: String, value: String, minLines: Int = 1, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), minLines = minLines, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

object MetadataSearch {
    private val client = OkHttpClient()
    fun search(q: String): List<BookMeta> {
        val url = "https://www.googleapis.com/books/v1/volumes?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&maxResults=8"
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val root = JSONObject(response.body?.string() ?: "{}")
            val items = root.optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val info = items.getJSONObject(i).optJSONObject("volumeInfo") ?: return@mapNotNull null
                val authors = info.optJSONArray("authors")
                val ids = info.optJSONArray("industryIdentifiers")
                var isbn = ""
                if (ids != null) for (j in 0 until ids.length()) {
                    val id = ids.getJSONObject(j)
                    if (id.optString("type").contains("ISBN")) { isbn = id.optString("identifier"); if (id.optString("type") == "ISBN_13") break }
                }
                BookMeta(
                    title = info.optString("title"),
                    author = if (authors != null) (0 until authors.length()).joinToString(", ") { authors.optString(it) } else "",
                    description = info.optString("description"),
                    publisher = info.optString("publisher"),
                    year = info.optString("publishedDate").take(4),
                    isbn = isbn,
                    language = info.optString("language"),
                    subjects = info.optJSONArray("categories")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: ""
                )
            }
        }
    }
}

object BookFile {
    private fun displayName(context: android.content.Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
        return uri.lastPathSegment ?: ""
    }

    fun read(context: android.content.Context, uri: Uri): Pair<BookMeta, String> {
        val name = displayName(context, uri).lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Порожній файл")
        return when {
            name.endsWith(".epub") || bytes.take(2).toByteArray().contentEquals(byteArrayOf(0x50,0x4b)) -> readEpub(bytes)
            name.endsWith(".fb2") || String(bytes.take(100).toByteArray()).contains("FictionBook") -> readFb2(bytes)
            name.endsWith(".pdf") -> BookMeta(title = "", description = "PDF: редагування властивостей буде додано у наступній версії.") to "PDF відкрито. Запис метаданих поки не підтримується."
            else -> BookMeta() to "Файл вибрано. Для цього формату читання/запис ще не реалізовано."
        }
    }

    private fun readEpub(bytes: ByteArray): Pair<BookMeta, String> {
        var opf = ""
        ZipInputStream(bytes.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name.endsWith(".opf")) opf = zin.readBytes().toString(Charsets.UTF_8)
            }
        }
        if (opf.isBlank()) return BookMeta() to "EPUB: файл не містить знайденого OPF."
        fun tag(t: String) = Regex("<dc:$t[^>]*>(.*?)</dc:$t>", RegexOption.DOT_MATCHES_ALL).find(opf)?.groupValues?.get(1)?.let(::unxml).orEmpty()
        return BookMeta(title = tag("title"), author = tag("creator"), description = tag("description"), publisher = tag("publisher"), year = tag("date").take(4), language = tag("language"), subjects = Regex("<dc:subject[^>]*>(.*?)</dc:subject>", RegexOption.DOT_MATCHES_ALL).findAll(opf).joinToString(", ") { unxml(it.groupValues[1]) }) to "Метадані EPUB прочитано."
    }

    
private fun readFb2(bytes: ByteArray): Pair<BookMeta, String> {
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(bytes.inputStream())

    fun text(tag: String): String {
        return doc.getElementsByTagName(tag)
            .item(0)?.textContent?.trim() ?: ""
    }

    val authorParts = listOf(
        text("first-name"),
        text("middle-name"),
        text("last-name")
    ).filter { part -> part.isNotBlank() }

    val author = authorParts.joinToString(" ")

    val meta = BookMeta(
        title = text("book-title"),
        author = author,
        description = text("annotation"),
        year = text("date").take(4),
        language = text("lang"),
        publisher = text("publisher")
    )

    return Pair(meta, "Метадані FB2 прочитано.")
}


    fun write(context: android.content.Context, uri: Uri, m: BookMeta): String {
        val name = displayName(context, uri).lowercase()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не вдалося прочитати файл")
        val updated = when {
            name.endsWith(".epub") || bytes.take(2).toByteArray().contentEquals(byteArrayOf(0x50,0x4b)) -> writeEpub(bytes, m)
            name.endsWith(".fb2") -> writeFb2(bytes, m)
            else -> error("Запис цього формату поки не підтримується. У цій версії запис доступний для EPUB та FB2.")
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(updated) } ?: error("Немає дозволу на запис")
        return "Готово: метадані збережено у файлі."
    }

    private fun writeEpub(bytes: ByteArray, m: BookMeta): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                entries[e.name] = zin.readBytes()
            }
        }
        val opfName = entries.keys.firstOrNull { it.endsWith(".opf") }
            ?: error("В EPUB не знайдено OPF-файл метаданих")
        entries[opfName] = updateOpf(entries[opfName]!!.toString(Charsets.UTF_8), m).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zout ->
            // EPUB: mimetype має бути першим записом і без стиснення.
            val mime = entries.remove("mimetype")
            if (mime != null) {
                val e = ZipEntry("mimetype")
                e.method = ZipEntry.STORED
                e.size = mime.size.toLong()
                e.compressedSize = mime.size.toLong()
                e.crc = java.util.zip.CRC32().apply { update(mime) }.value
                zout.putNextEntry(e); zout.write(mime); zout.closeEntry()
            }
            entries.forEach { (name, data) ->
                zout.putNextEntry(ZipEntry(name)); zout.write(data); zout.closeEntry()
            }
        }
        return out.toByteArray()
    }

    
private fun updateOpf(s: String, m: BookMeta): String {
    var out = s

    fun set(tag: String, value: String) {
        if (value.isBlank()) return

        val escaped = xml(value)
        val pattern = Regex(
            "(<dc:$tag\\b[^>]*>).*?(</dc:$tag>)",
            RegexOption.DOT_MATCHES_ALL
        )

        val match = pattern.find(out)

        out = if (match != null) {
            val replacement =
                match.groupValues[1] + escaped + match.groupValues[2]
            out.replaceRange(match.range, replacement)
        } else {
            out.replace(
                "</metadata>",
                "<dc:$tag>$escaped</dc:$tag></metadata>"
            )
        }
    }

    set("title", m.title)
    set("creator", m.author)
    set("description", m.description)
    set("publisher", m.publisher)
    set("date", m.year)
    set("language", m.language)

    return out
}


    private fun writeFb2(bytes: ByteArray, m: BookMeta): ByteArray {
        var s = bytes.toString(Charsets.UTF_8)
        fun set(tag: String, value: String) {
            if (value.isBlank()) return
            val pat = Regex("<$tag(?:\\s[^>]*)?>.*?</$tag>", RegexOption.DOT_MATCHES_ALL)
            s = if (pat.containsMatchIn(s)) pat.replaceFirst(s, "<$tag>${xml(value)}</$tag>")
            else s.replace("</title-info>", "<$tag>${xml(value)}</$tag></title-info>")
        }
        set("book-title", m.title); set("annotation", m.description); set("lang", m.language)
        return s.toByteArray(Charsets.UTF_8)
    }

    private fun xml(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun unxml(s: String) = s.replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&amp;","&").replace(Regex("<[^>]+>"),"").trim()
}
