package com.example.bookmeta

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile

data class ExtractionResult(
    val copied: Int,
    val failed: Int,
    val epub: Int,
    val fb2: Int,
    val errors: List<String>
)

object BookExtractor {
    /** Copies supported books into destination/EPUB and destination/FB2; originals stay untouched. */
    fun extract(
        resolver: ContentResolver,
        source: DocumentFile,
        destination: DocumentFile,
        recursive: Boolean = true
    ): ExtractionResult {
        val files = FolderScanner.scan(source, recursive)
        val epubFolder = destination.findFile("EPUB")?.takeIf { it.isDirectory }
            ?: destination.createDirectory("EPUB")
            ?: error("Не вдалося створити теку EPUB")
        val fb2Folder = destination.findFile("FB2")?.takeIf { it.isDirectory }
            ?: destination.createDirectory("FB2")
            ?: error("Не вдалося створити теку FB2")

        var copied = 0
        var epub = 0
        var fb2 = 0
        val errors = mutableListOf<String>()

        files.forEach { input ->
            val name = input.name ?: "book"
            try {
                val extension = name.substringAfterLast('.', "").lowercase()
                val targetFolder = if (extension == "epub") epubFolder else fb2Folder
                val mime = if (extension == "epub") "application/epub+zip" else "application/x-fictionbook+xml"
                val base = name.substringBeforeLast('.', name)
                var candidate = name
                var suffix = 2
                while (targetFolder.findFile(candidate) != null) {
                    candidate = "${base} (${suffix}).${extension}"
                    suffix++
                }
                val output = targetFolder.createFile(mime, candidate)
                    ?: error("Не вдалося створити файл призначення")
                try {
                    resolver.openInputStream(input.uri)?.use { sourceStream ->
                        resolver.openOutputStream(output.uri, "wt")?.use { targetStream ->
                            sourceStream.copyTo(targetStream)
                        } ?: error("Не вдалося відкрити файл для запису")
                    } ?: error("Не вдалося прочитати вихідний файл")
                    copied++
                    if (extension == "epub") epub++ else fb2++
                } catch (e: Exception) {
                    output.delete()
                    throw e
                }
            } catch (e: Exception) {
                errors.add("${name}: ${e.message ?: "помилка копіювання"}")
            }
        }
        return ExtractionResult(copied, errors.size, epub, fb2, errors.take(10))
    }
}
