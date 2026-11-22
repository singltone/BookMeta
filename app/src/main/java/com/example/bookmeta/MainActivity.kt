package com.example.bookmeta

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { BookMetaScreen() } }
}

@Composable
private fun BookMetaScreen() {
    val context= androidx.compose.ui.platform.LocalContext.current
    val scope= rememberCoroutineScope()
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf(BookMeta()) }
    var original by remember { mutableStateOf(BookMeta()) }
    var status by remember { mutableStateOf("Вибери EPUB або FB2") }
    var searchQuery by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    var recursive by remember { mutableStateOf(true) }
    val pickBook=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){ uri-> if(uri!=null){fileUri=uri;fileName=uri.lastPathSegment ?: "Книга";scope.launch { try { val loaded=BookFile.read(context.contentResolver,uri);meta=loaded.copy();original=loaded.copy();status="Файл відкрито" }catch(e:Exception){status="Помилка читання: ${e.message}"} } } }
    val pickFolder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){ uri-> if(uri!=null) { val dir=DocumentFile.fromTreeUri(context,uri); val list=dir?.let{FolderScanner.scan(it,recursive)}.orEmpty();status="Знайдено книг: ${list.size}" } }
    @Composable
fun field(label: String, value: String, update: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = update,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1
    )
}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("BookMeta",style=MaterialTheme.typography.headlineMedium)
        Text(status,style=MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick={pickBook.launch(arrayOf("application/epub+zip","application/octet-stream","text/xml","*/*"))}){Text("Відкрити книгу")}; OutlinedButton(onClick={pickFolder.launch(null)}){Text("Сканувати папку")} }
        if(fileName.isNotBlank()) Text(fileName,style=MaterialTheme.typography.titleSmall)
        OutlinedTextField(searchQuery,onValueChange={searchQuery=it},label={Text("Пошук книги (назва / ISBN)")},modifier=Modifier.fillMaxWidth())
        Button(onClick={scope.launch { status="Шукаю…"; try { val result=MetadataSearch.search(searchQuery); if(result!=null){meta=result;status="Знайдено дані Google Books — перевір перед збереженням" } else status="Нічого не знайдено" }catch(e:Exception){status="Помилка пошуку: ${e.message}"} }},enabled=searchQuery.isNotBlank()){Text("Знайти метадані")}
        field("Назва",meta.title){meta=meta.copy(title=it)}
        field("Автор",meta.author){meta=meta.copy(author=it)}
        field("ISBN",meta.isbn){meta=meta.copy(isbn=it)}
        field("Опис / анотація",meta.description){meta=meta.copy(description=it)}
        field("Видавництво",meta.publisher){meta=meta.copy(publisher=it)}
        field("Рік",meta.year){meta=meta.copy(year=it)}
        field("Мова",meta.language){meta=meta.copy(language=it)}
        field("Жанри / теги",meta.subjects){meta=meta.copy(subjects=it)}
        field("Серія",meta.series){meta=meta.copy(series=it)}
        field("Номер у серії",meta.seriesNumber){meta=meta.copy(seriesNumber=it)}
        field("URI обкладинки (поки як поле)",meta.coverUri){meta=meta.copy(coverUri=it)}
        Row(verticalAlignment= androidx.compose.ui.Alignment.CenterVertically) { Checkbox(checked=preview,onCheckedChange={preview=it});Text("Показати До / Після перед записом") }
        if(preview) { Text("ДО",style=MaterialTheme.typography.titleMedium); Text("${original.title} · ${original.author}");Text("ПІСЛЯ",style=MaterialTheme.typography.titleMedium);Text("${meta.title} · ${meta.author}") }
        Button(onClick={scope.launch { val uri=fileUri; if(uri==null){status="Спочатку відкрий книгу"}else if(preview){status="Перевір зміни та вимкни режим До / Після для запису"}else try{BookFile.write(context.contentResolver,uri,meta);status="Метадані записано"}catch(e:Exception){status="Помилка запису: ${e.message}"} }},modifier=Modifier.fillMaxWidth(),enabled=fileUri!=null){Text("Зберегти зміни")}
        Text("Сканування папок: рекурсивно",style=MaterialTheme.typography.titleSmall)
        Row(verticalAlignment= androidx.compose.ui.Alignment.CenterVertically){Checkbox(checked=recursive,onCheckedChange={recursive=it});Text("Включати підпапки")}
        Text("Підтримуються EPUB та FB2. Перед редагуванням збережи резервну копію книги.",style=MaterialTheme.typography.bodySmall)
    }
}
