package com.example.bookmeta

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class BatchBookItem(
    val uri: Uri,
    val name: String,
    val selected: Boolean = true
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { BookMetaScreen() } }
    }
}

@Composable
private fun BookMetaScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bookUri by remember { mutableStateOf<Uri?>(null) }
    var bookName by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf(BookMeta()) }
    var original by remember { mutableStateOf(BookMeta()) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<BookMeta>()) }
    var status by remember { mutableStateOf("Оберіть EPUB або FB2 файл") }
    var recursive by remember { mutableStateOf(true) }
    var fillOnlyEmpty by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var batchItems by remember { mutableStateOf(emptyList<BatchBookItem>()) }
    var busy by remember { mutableStateOf(false) }
    var recentLogs by remember { mutableStateOf(OperationLog.recent(context.filesDir)) }
    var backups by remember { mutableStateOf(emptyList<File>()) }
    var restoreCandidate by remember { mutableStateOf<File?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showExtractor by remember { mutableStateOf(false) }
    var extractionSource by remember { mutableStateOf<Uri?>(null) }
    var extractionDestination by remember { mutableStateOf<Uri?>(null) }
    var extractionRecursive by remember { mutableStateOf(true) }
    var extractionSortByAuthor by remember { mutableStateOf(false) }
    var batchSourceUri by remember { mutableStateOf<Uri?>(null) }

    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            bookUri = picked
            context.contentResolver.takePersistableUriPermission(
                picked,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            bookName = DocumentFile.fromSingleUri(context, picked)?.name ?: picked.lastPathSegment ?: "Книга"
            backups = BackupManager.list(File(context.filesDir, "backups"), bookName)
            scope.launch {
                busy = true
                try {
                    val loaded = withContext(Dispatchers.IO) { BookFile.read(context.contentResolver, picked) }
                    meta = loaded
                    original = loaded.copy()
                    status = "Книгу завантажено"
                } catch (e: Exception) {
                    status = "Помилка відкриття: ${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            context.contentResolver.takePersistableUriPermission(
                picked,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            meta = meta.copy(coverUri = picked.toString(), removeCover = false)
            status = "Обкладинку вибрано. Вона буде вбудована під час збереження."
        }
    }

    val extractionSourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
            context.contentResolver.takePersistableUriPermission(
                picked,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            extractionSource = picked
        }
    }
    val extractionDestinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
            context.contentResolver.takePersistableUriPermission(
                picked,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            extractionDestination = picked
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
            batchSourceUri = picked
            scope.launch {
                busy = true
                try {
                    context.contentResolver.takePersistableUriPermission(
                        picked,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val found = withContext(Dispatchers.IO) {
                        DocumentFile.fromTreeUri(context, picked)?.let {
                            FolderScanner.scan(it, recursive).mapNotNull { file ->
                                val fileName = file.name ?: return@mapNotNull null
                                BatchBookItem(file.uri, fileName)
                            }
                        }.orEmpty()
                    }
                    batchItems = found
                    status = "Знайдено книг: ${found.size}. Познач потрібні для пакетної обробки."
                } catch (e: Exception) {
                    status = "Помилка сканування: ${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    @Composable
    fun updateField(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            minLines = if (label == "Анотація") 3 else 1
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BookMeta", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menuExpanded = true }, enabled = !busy) { Text("☰") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Редактор метаданих") },
                        onClick = { menuExpanded = false; showExtractor = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Витяг і сортування книг") },
                        onClick = { menuExpanded = false; showExtractor = true }
                    )
                }
            }
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { bookPicker.launch(arrayOf("application/epub+zip", "application/octet-stream", "text/xml", "*/*")) }, enabled = !busy) {
                Text("Відкрити книгу")
            }
            OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy) {
                Text("Сканувати папку")
            }
        }
        if (bookName.isNotBlank()) Text(bookName, style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Назва або ISBN для пошуку") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )
        Button(
            enabled = query.isNotBlank() && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    status = "Шукаю метадані…"
                    try {
                        val results = withContext(Dispatchers.IO) { MetadataSearch.searchAll(query) }
                        searchResults = results
                        status = if (results.isNotEmpty()) "Знайдено варіантів: ${results.size}. Обери потрібне видання." else "Нічого не знайдено"
                    } catch (e: Exception) {
                        status = "Помилка пошуку: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }
        ) { Text("Пошук Google Books") }

        if (searchResults.isNotEmpty()) {
            Text("Результати пошуку", style = MaterialTheme.typography.titleMedium)
            searchResults.forEachIndexed { index, candidate ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        meta = candidate
                        status = "Обрано: ${candidate.title}. Перевір метадані."
                        searchResults = emptyList()
                    }
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(candidate.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOf(candidate.author, candidate.year, candidate.publisher)
                                .filter { it.isNotBlank() }.joinToString(" · ")
                                .ifBlank { "Автор або рік не вказані" },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (candidate.isbn.isNotBlank()) {
                            Text("ISBN: ${candidate.isbn}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Використати це видання", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            TextButton(onClick = { searchResults = emptyList() }, enabled = !busy) {
                Text("Закрити результати")
            }
        }

        updateField("Назва", meta.title) { meta = meta.copy(title = it) }
        updateField("Автори", meta.author) { meta = meta.copy(author = it) }
        updateField("ISBN", meta.isbn) { meta = meta.copy(isbn = it) }
        updateField("Анотація", meta.description) { meta = meta.copy(description = it) }
        updateField("Видавництво", meta.publisher) { meta = meta.copy(publisher = it) }
        updateField("Рік", meta.year) { meta = meta.copy(year = it) }
        updateField("Мова", meta.language) { meta = meta.copy(language = it) }
        updateField("Жанри / теги (через кому)", meta.subjects) { meta = meta.copy(subjects = it) }
        updateField("Серія", meta.series) { meta = meta.copy(series = it) }
        updateField("Номер у серії", meta.seriesNumber) { meta = meta.copy(seriesNumber = it) }

        OutlinedButton(
            onClick = { coverPicker.launch(arrayOf("image/*")) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        ) { Text(if (meta.coverUri.isBlank() && meta.coverData == null) "Обрати обкладинку" else "Замінити обкладинку") }
        if (meta.coverUri.isNotBlank() || meta.coverData != null) {
            TextButton(
                onClick = {
                    meta = meta.copy(coverUri = "", coverData = null, coverUrl = "", removeCover = true)
                    status = "Обкладинку буде видалено під час збереження."
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Видалити обкладинку") }
        }
        if (meta.coverUrl.isNotBlank()) {
            OutlinedButton(
                onClick = {
                    val url = meta.coverUrl
                    scope.launch {
                        busy = true
                        status = "Завантажую обкладинку…"
                        try {
                            val bytes = withContext(Dispatchers.IO) { CoverManager.downloadAsJpeg(url) }
                                ?: error("Сервер не повернув коректне зображення")
                            meta = meta.copy(coverUri = "", coverData = bytes, removeCover = false)
                            status = "Обкладинку завантажено. Вона буде вбудована у книгу під час збереження."
                        } catch (e: Exception) {
                            status = "Не вдалося завантажити обкладинку: ${e.message}"
                        } finally { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) { Text("Завантажити обкладинку з Google Books") }
        }
        val coverPreview = remember(meta.coverUri, meta.coverData) {
            runCatching {
                if (meta.coverUri.isNotBlank()) {
                    CoverManager.createPreview(context.contentResolver, Uri.parse(meta.coverUri))
                } else {
                    meta.coverData?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }.getOrNull()
        }
        if (coverPreview != null) {
            Image(
                bitmap = coverPreview.asImageBitmap(),
                contentDescription = "Попередній перегляд обкладинки",
                modifier = Modifier.heightIn(max = 220.dp).fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        } else if (meta.coverUri.isBlank() && meta.coverData == null) {
            Text("У файлі не знайдено вбудовану обкладинку.")
        }

        Button(
            enabled = bookUri != null && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val selectedUri = bookUri ?: return@Button
                val selectedName = bookName
                val updated = meta.copy()
                scope.launch {
                    busy = true
                    try {
                        val (backup, verifiedMeta) = withContext(Dispatchers.IO) {
                            val file = BackupManager.backup(
                                context.contentResolver, selectedUri,
                                File(context.filesDir, "backups"), selectedName
                            )
                            BookFile.write(context.contentResolver, selectedUri, updated)
                            val verified = BookFile.read(context.contentResolver, selectedUri)
                            val fieldsOk = listOf(
                                updated.title to verified.title,
                                updated.author to verified.author,
                                updated.isbn to verified.isbn,
                                updated.publisher to verified.publisher,
                                updated.year to verified.year,
                                updated.language to verified.language,
                                updated.subjects to verified.subjects,
                                updated.series to verified.series,
                                updated.seriesNumber to verified.seriesNumber
                            ).all { (expected, actual) ->
                                expected.trim().replace(Regex("\\s+"), " ") ==
                                    actual.trim().replace(Regex("\\s+"), " ")
                            }
                            if (!fieldsOk) error("Файл записано, але повторна перевірка метаданих виявила розбіжності. Є резервна копія.")
                            OperationLog.add(context.filesDir, "Збереження", selectedName, true, "Резервна копія: ${file.name}; повторне читання успішне")
                            file to verified
                        }
                        meta = verifiedMeta
                        original = verifiedMeta.copy()
                        backups = BackupManager.list(File(context.filesDir, "backups"), selectedName)
                        recentLogs = OperationLog.recent(context.filesDir)
                        status = "Збережено. Резервна копія: ${backup.name}"
                    } catch (e: Exception) {
                        OperationLog.add(context.filesDir, "Збереження", selectedName, false, e.message.orEmpty())
                        recentLogs = OperationLog.recent(context.filesDir)
                        status = "Помилка запису: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }
        ) { Text("Зберегти зміни в книзі") }

        if (showExtractor) {
            AlertDialog(
                onDismissRequest = { if (!busy) showExtractor = false },
                title = { Text("Витяг і сортування книг") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Книги копіюються у підтеки EPUB і FB2. Файли з помилками переміщуються у «Помилка» у вихідній теці.")
                        OutlinedButton(onClick = { extractionSourcePicker.launch(null) }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                            Text(if (extractionSource == null) "Вибрати вихідну теку" else "Вихідну теку вибрано ✓")
                        }
                        OutlinedButton(onClick = { extractionDestinationPicker.launch(null) }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                            Text(if (extractionDestination == null) "Вибрати теку призначення" else "Теку призначення вибрано ✓")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = extractionRecursive, onCheckedChange = { extractionRecursive = it }, enabled = !busy)
                            Text("Шукати також у підтеках")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = extractionSortByAuthor, onCheckedChange = { extractionSortByAuthor = it }, enabled = !busy)
                            Text("Сортувати за автором")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy && extractionSource != null && extractionDestination != null,
                        onClick = {
                            val sourceUri = extractionSource ?: return@TextButton
                            val destinationUri = extractionDestination ?: return@TextButton
                            scope.launch {
                                busy = true
                                status = "Витягую та сортую книги…"
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        val source = DocumentFile.fromTreeUri(context, sourceUri) ?: error("Не вдалося відкрити вихідну теку")
                                        val destination = DocumentFile.fromTreeUri(context, destinationUri) ?: error("Не вдалося відкрити теку призначення")
                                        BookExtractor.extract(context.contentResolver, source, destination, extractionRecursive, extractionSortByAuthor)
                                    }
                                    OperationLog.add(context.filesDir, "Витяг книг", "Папка", true, "Скопійовано: ${result.copied}; EPUB: ${result.epub}; FB2: ${result.fb2}; помилок: ${result.failed}")
                                    recentLogs = OperationLog.recent(context.filesDir)
                                    status = "Готово: ${result.copied} книг (EPUB: ${result.epub}, FB2: ${result.fb2}), помилок: ${result.failed}. Переміщено до «Помилка»: ${result.movedToError}."
                                    if (result.errors.isNotEmpty()) status += " " + result.errors.take(3).joinToString("; ")
                                    showExtractor = false
                                } catch (e: Exception) {
                                    status = "Помилка витягу: ${e.message}"
                                } finally { busy = false }
                            }
                        }
                    ) { Text("Почати витяг") }
                },
                dismissButton = { TextButton(onClick = { showExtractor = false }, enabled = !busy) { Text("Закрити") } }
            )
        }

        HorizontalDivider()
        Text("Пакетна обробка", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = recursive, onCheckedChange = { recursive = it }, enabled = !busy)
            Text("Сканувати також підпапки")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = fillOnlyEmpty, onCheckedChange = { fillOnlyEmpty = it }, enabled = !busy)
            Text("Заповнювати лише порожні поля")
        }
        if (batchItems.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Знайдено: ${batchItems.size}; вибрано: ${batchItems.count { it.selected }}")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val select = batchItems.any { !it.selected }
                    batchItems = batchItems.map { it.copy(selected = select) }
                }, enabled = !busy) { Text("Усі / жодної") }
            }
            batchItems.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = item.selected,
                        onCheckedChange = { checked ->
                            batchItems = batchItems.toMutableList().also {
                                it[index] = item.copy(selected = checked)
                            }
                        },
                        enabled = !busy
                    )
                    Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && batchItems.any { it.selected },
                onClick = {
                    val selected = batchItems.filter { it.selected }
                    scope.launch {
                        busy = true
                        var updatedCount = 0
                        var noMetadataCount = 0
                        var failedCount = 0
                        selected.forEach { item ->
                            try {
                                val found = withContext(Dispatchers.IO) {
                                    val current = BookFile.read(context.contentResolver, item.uri)
                                    val fileTitle = item.name.substringBeforeLast('.', item.name)
                                    val titleSearch = listOf(current.title.ifBlank { fileTitle }, current.author)
                                        .filter { it.isNotBlank() }.joinToString(" ")
                                    val candidate = current.isbn.takeIf { it.isNotBlank() }
                                        ?.let { isbn -> MetadataSearch.searchAll(isbn).firstOrNull() }
                                        ?: titleSearch.takeIf { it.isNotBlank() }
                                            ?.let { MetadataSearch.searchAll(it).firstOrNull() }
                                    candidate?.let { candidate ->
                                        val updated = BatchEditor.apply(candidate, current, true)
                                        val backup = BackupManager.backup(
                                            context.contentResolver, item.uri,
                                            File(context.filesDir, "backups"), item.name
                                        )
                                        BookFile.write(context.contentResolver, item.uri, updated)
                                        OperationLog.add(context.filesDir, "Автопошук метаданих", item.name, true, "Резервна копія: ${backup.name}")
                                        candidate
                                    }
                                }
                                if (found == null) {
                                    val moved = withContext(Dispatchers.IO) {
                                        val root = batchSourceUri?.let { DocumentFile.fromTreeUri(context, it) }
                                        val file = root?.let { FolderScanner.scan(it, true).firstOrNull { doc -> doc.uri == item.uri } }
                                        root != null && file != null && FailureFileManager.moveOrCopyToErrorFolder(context.contentResolver, root, file)
                                    }
                                    if (moved) noMetadataCount++ else failedCount++
                                    OperationLog.add(context.filesDir, "Автопошук метаданих", item.name, false,
                                        if (moved) "Метадані не знайдено; переміщено до «Помилка»" else "Метадані не знайдено; не вдалося перемістити файл")
                                } else updatedCount++
                            } catch (e: Exception) {
                                val moved = withContext(Dispatchers.IO) {
                                    val root = batchSourceUri?.let { DocumentFile.fromTreeUri(context, it) }
                                    val file = root?.let { FolderScanner.scan(it, true).firstOrNull { doc -> doc.uri == item.uri } }
                                    root != null && file != null && FailureFileManager.moveOrCopyToErrorFolder(context.contentResolver, root, file)
                                }
                                failedCount++
                                OperationLog.add(context.filesDir, "Автопошук метаданих", item.name, false, e.message.orEmpty())
                            }
                        }
                        recentLogs = OperationLog.recent(context.filesDir)
                        status = "Автопошук завершено: оновлено $updatedCount; без метаданих переміщено: $noMetadataCount; інші помилки: $failedCount."
                        busy = false
                    }
                }
            ) { Text("Знайти метадані для кожної книги") }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && batchItems.any { it.selected } && hasMetadataToApply(meta),
                onClick = {
                    val selected = batchItems.filter { it.selected }
                    val template = meta.copy()
                    scope.launch {
                        busy = true
                        var saved = 0
                        val errors = mutableListOf<String>()
                        selected.forEach { item ->
                            try {
                                withContext(Dispatchers.IO) {
                                    val current = BookFile.read(context.contentResolver, item.uri)
                                    val updated = BatchEditor.apply(template, current, fillOnlyEmpty)
                                    val backup = BackupManager.backup(
                                        context.contentResolver, item.uri,
                                        File(context.filesDir, "backups"), item.name
                                    )
                                    BookFile.write(context.contentResolver, item.uri, updated)
                                    OperationLog.add(context.filesDir, "Пакетне збереження", item.name, true, "Резервна копія: ${backup.name}")
                                }
                                saved++
                            } catch (e: Exception) {
                                val moved = withContext(Dispatchers.IO) {
                                    val root = batchSourceUri?.let { DocumentFile.fromTreeUri(context, it) }
                                    val failedFile = root?.let { folder ->
                                        FolderScanner.scan(folder, true).firstOrNull { doc -> doc.uri == item.uri }
                                    }
                                    root != null && failedFile != null && FailureFileManager.moveOrCopyToErrorFolder(context.contentResolver, root, failedFile)
                                }
                                OperationLog.add(context.filesDir, "Пакетне збереження", item.name, false, e.message.orEmpty())
                                errors.add("${item.name}: ${e.message}" + if (moved) " (переміщено до «Помилка»)" else "")
                            }
                        }
                        recentLogs = OperationLog.recent(context.filesDir)
                        status = "Пакетно збережено: $saved з ${selected.size}. Резервні копії створено."
                        if (errors.isNotEmpty()) status += " Помилки: " + errors.take(3).joinToString("; ")
                        busy = false
                    }
                }
            ) { Text("Застосувати метадані до вибраних книг") }
        } else {
            Text("Спочатку вибери папку, щоб завантажити список EPUB/FB2.")
        }

        HorizontalDivider()
        Text("Перегляд змін", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showPreview, onCheckedChange = { showPreview = it }, enabled = !busy)
            Text("Показати До / Після")
        }
        if (showPreview) {
            Text("ДО: ${original.title} · ${original.author}")
            Text("ПІСЛЯ: ${meta.title} · ${meta.author}")
            Text("Серія: ${original.series} [${original.seriesNumber}] → ${meta.series} [${meta.seriesNumber}]")
            Text("ISBN: ${original.isbn} → ${meta.isbn}")
        }



        HorizontalDivider()
        Text("Резервні копії та історія", style = MaterialTheme.typography.titleLarge)
        if (bookUri != null) {
            Text("Копії для поточної книги: ${backups.size}")
            backups.take(5).forEach { backup ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(backup.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { restoreCandidate = backup }, enabled = !busy) { Text("Відновити") }
                }
            }
            if (backups.isEmpty()) Text("Для цієї книги резервних копій ще немає.")
        } else Text("Відкрий книгу, щоб побачити її резервні копії.")
        Text("Останні операції", style = MaterialTheme.typography.titleMedium)
        if (recentLogs.isEmpty()) Text("Історія поки порожня.", style = MaterialTheme.typography.bodySmall)
        else recentLogs.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { recentLogs = OperationLog.recent(context.filesDir, 100) }, enabled = !busy) {
            Text("Оновити історію")
        }

        restoreCandidate?.let { candidate ->
            AlertDialog(
                onDismissRequest = { restoreCandidate = null },
                title = { Text("Відновити резервну копію?") },
                text = { Text("Поточний вміст книги буде замінено копією:\n${candidate.name}") },
                confirmButton = {
                    TextButton(onClick = {
                        val target = bookUri
                        if (target != null) {
                            scope.launch {
                                busy = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        val safetyCopy = BackupManager.backup(
                                            context.contentResolver, target,
                                            File(context.filesDir, "backups"), bookName
                                        )
                                        BackupManager.restore(context.contentResolver, target, candidate)
                                        OperationLog.add(context.filesDir, "Відновлення", bookName, true, "Перед відновленням збережено: ${safetyCopy.name}")
                                    }
                                    val restored = withContext(Dispatchers.IO) { BookFile.read(context.contentResolver, target) }
                                    meta = restored
                                    original = restored.copy()
                                    backups = BackupManager.list(File(context.filesDir, "backups"), bookName)
                                    recentLogs = OperationLog.recent(context.filesDir)
                                    status = "Резервну копію відновлено."
                                } catch (e: Exception) {
                                    OperationLog.add(context.filesDir, "Відновлення", bookName, false, e.message.orEmpty())
                                    recentLogs = OperationLog.recent(context.filesDir)
                                    status = "Помилка відновлення: ${e.message}"
                                } finally {
                                    restoreCandidate = null
                                    busy = false
                                }
                            }
                        } else restoreCandidate = null
                    }) { Text("Відновити") }
                },
                dismissButton = { TextButton(onClick = { restoreCandidate = null }) { Text("Скасувати") } }
            )
        }

        Text(
            "Підтримуються EPUB і FB2. Перед кожним записом створюється резервна копія у приватному сховищі програми.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun hasMetadataToApply(meta: BookMeta): Boolean =
    listOf(
        meta.title, meta.author, meta.description, meta.publisher, meta.year,
        meta.isbn, meta.language, meta.subjects, meta.series, meta.seriesNumber,
        meta.coverUri
    ).any { it.isNotBlank() } || meta.coverData != null
