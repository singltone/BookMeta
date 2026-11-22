package com.example.bookmeta

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
object BookRepository {
    fun scan(root: DocumentFile, includeSubfolders: Boolean = true) = FolderScanner.scan(root,includeSubfolders)
    fun load(resolver: ContentResolver, uri: Uri) = BookFile.read(resolver,uri)
    fun save(resolver: ContentResolver, uri: Uri, meta: BookMeta) = BookFile.write(resolver,uri,meta)
}
