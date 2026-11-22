package com.example.bookmeta

import androidx.documentfile.provider.DocumentFile

object FolderScanner {
    fun scan(root: DocumentFile, recursive: Boolean = true): List<DocumentFile> {
        val found = mutableListOf<DocumentFile>()
        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { item ->
                if (item.isDirectory && recursive) walk(item)
                else if (item.isFile && (item.name?.lowercase()?.endsWith(".epub") == true || item.name?.lowercase()?.endsWith(".fb2") == true)) found += item
            }
        }
        walk(root)
        return found
    }
}
