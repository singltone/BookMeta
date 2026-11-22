package com.example.bookmeta

import android.content.ContentResolver
import android.net.Uri
import java.io.File

object BackupManager {
    /** Copies original bytes into app-private files/backups before a write. Returns backup file. */
    fun backup(resolver: ContentResolver, uri: Uri, backupDir: File, displayName: String): File {
        backupDir.mkdirs()
        val safe=displayName.replace(Regex("[^A-Za-z0-9._-]"),"_")
        val file=File(backupDir, "${System.currentTimeMillis()}_$safe")
        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } } ?: error("Cannot read original file for backup")
        return file
    }
}
