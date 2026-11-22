package com.example.bookmeta

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class ScannedBook(val uri: Uri, val displayName: String)
object FolderScanner {
    fun scan(folder: DocumentFile, recursive: Boolean): List<ScannedBook> {
        val found=mutableListOf<ScannedBook>()
        fun visit(dir:DocumentFile) { dir.listFiles().forEach { f-> if(f.isDirectory && recursive) visit(f) else if(f.isFile && (f.name?.endsWith(".epub",true)==true || f.name?.endsWith(".fb2",true)==true)) found.add(ScannedBook(f.uri,f.name ?: "Book")) } }
        visit(folder);return found
    }
}
