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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { BookMetaScreen() } }
}

@Composable
private fun BookMetaScreen() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf(BookMeta()) }
    var original by remember { mutableStateOf(BookMeta()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Оберіть EPUB або FB2 файл") }
    var recursive by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf(false) }
    var fillOnlyEmpty by remember { mutableStateOf(false) }
    val bookPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if(picked!=null){ uri=picked; name=picked.lastPathSegment ?: "Книга"; scope.launch { try { val loaded=BookFile.read(context.contentResolver,picked);meta=loaded;original=loaded.copy();status="Книгу завантажено" }catch(e:Exception){status="Помилка відкриття: ${e.message}"} } }
    }
    val coverPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked -> if(picked!=null) meta=meta.copy(coverUri=picked.toString()) }
    val folderPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if(picked!=null){ context.contentResolver.takePersistableUriPermission(picked,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION); val root=DocumentFile.fromTreeUri(context,picked); val count=root?.let{FolderScanner.scan(it,recursive).size} ?: 0;status="У папці знайдено EPUB/FB2: $count" }
    }
    @Composable fun field(label:String,value:String,onChange:(String)->Unit){ OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("BookMeta",style=MaterialTheme.typography.headlineLarge)
        Text(status)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick={bookPicker.launch(arrayOf("application/epub+zip","application/octet-stream","text/xml","*/*"))}){Text("Відкрити книгу")}; OutlinedButton(onClick={folderPicker.launch(null)}){Text("Сканувати папку")} }
        if(name.isNotBlank()) Text(name,style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(query,onValueChange={query=it},label={Text("Назва або ISBN для пошуку")},modifier=Modifier.fillMaxWidth())
        Button(enabled=query.isNotBlank(),onClick={scope.launch(Dispatchers.IO){try{status="Пошук…";val result=MetadataSearch.search(query);launch(Dispatchers.Main){if(result!=null){meta=result;status="Знайдено. Перевір дані перед записом"}else status="Нічого не знайдено"}}catch(e:Exception){launch(Dispatchers.Main){status="Помилка пошуку: ${e.message}"}}}}){Text("Пошук Google Books")}
        field("Назва",meta.title){meta=meta.copy(title=it)}
        field("Автор",meta.author){meta=meta.copy(author=it)}
        field("ISBN",meta.isbn){meta=meta.copy(isbn=it)}
        field("Анотація",meta.description){meta=meta.copy(description=it)}
        field("Видавництво",meta.publisher){meta=meta.copy(publisher=it)}
        field("Рік",meta.year){meta=meta.copy(year=it)}
        field("Мова",meta.language){meta=meta.copy(language=it)}
        field("Жанри / теги",meta.subjects){meta=meta.copy(subjects=it)}
        field("Серія",meta.series){meta=meta.copy(series=it)}
        field("Номер у серії",meta.seriesNumber){meta=meta.copy(seriesNumber=it)}
        field("URI обкладинки",meta.coverUri){meta=meta.copy(coverUri=it)}
        OutlinedButton(onClick={coverPicker.launch("image/*")},modifier=Modifier.fillMaxWidth()){Text("Обрати обкладинку з пристрою")}
        Row(verticalAlignment= androidx.compose.ui.Alignment.CenterVertically){Checkbox(checked=recursive,onCheckedChange={recursive=it});Text("Сканувати підпапки")}
        Row(verticalAlignment= androidx.compose.ui.Alignment.CenterVertically){Checkbox(checked=fillOnlyEmpty,onCheckedChange={fillOnlyEmpty=it});Text("Заповнювати лише порожні поля (режим для пакетних змін)")}
        Row(verticalAlignment= androidx.compose.ui.Alignment.CenterVertically){Checkbox(checked=preview,onCheckedChange={preview=it});Text("Перегляд До / Після")}
        if(preview){Text("ДО: ${original.title} · ${original.author}");Text("ПІСЛЯ: ${meta.title} · ${meta.author}")}
        Button(enabled=uri!=null,onClick={scope.launch { try { val selected=uri!!;val backup=BackupManager.backup(context.contentResolver,selected,java.io.File(context.filesDir,"backups"),name);BookFile.write(context.contentResolver,selected,meta);status="Збережено. Резервна копія: ${backup.name}" }catch(e:Exception){status="Помилка запису: ${e.message}"} }},modifier=Modifier.fillMaxWidth()){Text("Зберегти книгу")}
        Text("Підтримуються EPUB і FB2. Обкладинка вбудовується у файл під час збереження.",style=MaterialTheme.typography.bodySmall)
    }
}
