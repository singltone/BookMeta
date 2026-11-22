package com.example.bookmeta

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile

data class ExtractionResult(
    val copied: Int,
    val failed: Int,
    val epub: Int,
    val fb2: Int,
    val errors: List<String>,
    val movedToError: Int = 0
)

object BookExtractor {
    /** Copies supported books into destination/EPUB and destination/FB2; originals are moved only when processing fails. */
    fun extract(
        resolver: ContentResolver,
        source: DocumentFile,
        destination: DocumentFile,
        recursive: Boolean = true,
        sortByAuthor: Boolean = false
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
        var movedToError = 0
        val errors = mutableListOf<String>()

        files.forEach { input ->
            val name = input.name ?: "book"
            try {
                val extension = name.substringAfterLast('.', "").lowercase()
                val formatFolder = if (extension == "epub") epubFolder else fb2Folder
                val mime = if (extension == "epub") "application/epub+zip" else "application/x-fictionbook+xml"
                val authorFolder = if (sortByAuthor) {
                    val author = runCatching { BookFile.read(resolver, input.uri).author }
                        .getOrDefault("")
                        .substringBefore(',')
                        .trim()
                        .ifBlank { "Невідомий автор" }
                        .replace(Regex("""[\\/:*?"<>|]"""), "_")
                    formatFolder.findFile(author)?.takeIf { it.isDirectory }
                        ?: formatFolder.createDirectory(author)
                        ?: error("Не вдалося створити теку автора: $author")
                } else formatFolder

                val base = name.substringBeforeLast('.', name)
                // First copy stays in the current folder; same-name copies are grouped in a title folder.
                val actualFolder = if (authorFolder.findFile(name) != null) {
                    findOrCreateDuplicateFolder(authorFolder, base)
                } else authorFolder

                var candidate = name
                var suffix = 2
                while (actualFolder.findFile(candidate) != null) {
                    candidate = "${base} (${suffix}).${extension}"
                    suffix++
                }
                val output = actualFolder.createFile(mime, candidate)
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
                val moved = runCatching { FailureFileManager.moveToErrorFolder(resolver, source, input) }.getOrDefault(false)
                if (moved) movedToError++
                errors.add("${name}: ${e.message ?: "помилка копіювання"}")
            }
        }
        return ExtractionResult(copied, errors.size, epub, fb2, errors.take(10), movedToError)
    }

    private fun findOrCreateDuplicateFolder(parent: DocumentFile, bookName: String): DocumentFile {
        var folderName = bookName.ifBlank { "Без назви" }
        var suffix = 2
        while (true) {
            val existing = parent.findFile(folderName)
            if (existing == null) {
                return parent.createDirectory(folderName)
                    ?: error("Не вдалося створити теку дублікатів: $folderName")
            }
            if (existing.isDirectory) return existing
            folderName = "$bookName (дублікати ${suffix})"
            suffix++
        }
    }
}

object FailureFileManager {
    /** Copies a failed book into sourceRoot/Помилка, then removes the original only after a successful copy. */
    fun moveToErrorFolder(
        resolver: ContentResolver,
        sourceRoot: DocumentFile,
        input: DocumentFile
    ): Boolean {
        val errorFolder = sourceRoot.findFile("Помилка")?.takeIf { it.isDirectory }
            ?: sourceRoot.createDirectory("Помилка")
            ?: return false
        val originalName = input.name ?: "book"
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val extension = if (dot > 0) originalName.substring(dot) else ""
        var candidate = originalName
        var suffix = 2
        while (errorFolder.findFile(candidate) != null) {
            candidate = "$base ($suffix)$extension"
            suffix++
        }
        val mime = if (extension.equals(".epub", true)) "application/epub+zip" else "application/x-fictionbook+xml"
        val copy = errorFolder.createFile(mime, candidate) ?: return false
        return try {
            resolver.openInputStream(input.uri)?.use { source ->
                resolver.openOutputStream(copy.uri, "wt")?.use { target ->
                    source.copyTo(target)
                } ?: error("Не вдалося записати файл")
            } ?: error("Не вдалося прочитати файл")
            if (input.delete()) true else {
                copy.delete()
                false
            }
        } catch (_: Exception) {
            copy.delete()
            false
        }
    }
}
