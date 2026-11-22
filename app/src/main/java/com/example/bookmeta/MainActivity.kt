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
    var status by remember { mutableStateOf("Оберіть EPUB або FB2 файл") }
    var recursive by remember { mutableStateOf(true) }
    var fillOnlyEmpty by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var batchItems by remember { mutableStateOf(emptyList<BatchBookItem>()) }
    var busy by remember { mutableStateOf(false) }
    var recentLogs by remember { mutableStateOf(OperationLog.recent(context.filesDir)) }
    var backups by remember { mutableStateOf(emptyList<File>()) }
    var restoreCandidate by remember { mutableStateOf<File?>(null) }

    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            bookUri = picked
            context.contentResolver.takePersistableUriPermission(
                picked,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            bookName = picked.lastPathSegment ?: "Книга"
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
            meta = meta.copy(coverUri = picked.toString())
            status = "Обкладинку вибрано. Вона буде вбудована під час збереження."
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) {
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
        Text("BookMeta", style = MaterialTheme.typography.headlineLarge)
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
                        val result = withContext(Dispatchers.IO) { MetadataSearch.search(query) }
                        if (result != null) {
                            meta = result
                            status = "Знайдено. Перевір дані перед застосуванням."
                        } else status = "Нічого не знайдено"
                    } catch (e: Exception) {
                        status = "Помилка пошуку: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }
        ) { Text("Пошук Google Books") }

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
        ) { Text(if (meta.coverUri.isBlank()) "Обрати обкладинку" else "Замінити обкладинку") }
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
                                OperationLog.add(context.filesDir, "Пакетне збереження", item.name, false, e.message.orEmpty())
                                errors.add("${item.name}: ${e.message}")
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
                        val backup = withContext(Dispatchers.IO) {
                            val file = BackupManager.backup(
                                context.contentResolver, selectedUri,
                                File(context.filesDir, "backups"), selectedName
                            )
                            BookFile.write(context.contentResolver, selectedUri, updated)
                            OperationLog.add(context.filesDir, "Збереження", selectedName, true, "Резервна копія: ${file.name}")
                            file
                        }
                        original = updated.copy()
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
        ) { Text("Зберегти книгу") }

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
    ).any { it.isNotBlank() }
