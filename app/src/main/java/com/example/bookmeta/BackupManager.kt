package com.example.bookmeta

import android.content.ContentResolver
import android.net.Uri
import java.io.File

object BackupManager {
    /** Copies original bytes into app-private files/backups before a write. Returns backup file. */
    fun backup(resolver: ContentResolver, uri: Uri, backupDir: File, displayName: String): File {
        backupDir.mkdirs()
        val safe = safeName(displayName)
        val file = File(backupDir, "${System.currentTimeMillis()}_$safe")
        resolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot read original file for backup")
        return file
    }

    /** Lists backups belonging to the selected original filename, newest first. */
    fun list(backupDir: File, displayName: String): List<File> {
        if (!backupDir.isDirectory) return emptyList()
        val suffix = "_${safeName(displayName)}"
        return backupDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(suffix) }
            ?.sortedByDescending { it.name.substringBefore('_').toLongOrNull() ?: 0L }
            .orEmpty()
    }

    /** Replaces the selected book with the exact bytes stored in a backup. */
    fun restore(resolver: ContentResolver, uri: Uri, backupFile: File) {
        require(backupFile.isFile) { "Резервну копію не знайдено" }
        resolver.openOutputStream(uri, "wt")?.use { output ->
            backupFile.inputStream().use { input -> input.copyTo(output) }
            output.flush()
        } ?: error("Не вдалося відкрити книгу для відновлення")
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
