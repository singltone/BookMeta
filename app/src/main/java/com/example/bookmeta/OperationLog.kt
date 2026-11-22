package com.example.bookmeta

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OperationLog {
    private const val FILE_NAME = "operation-log.txt"

    fun add(filesDir: File, action: String, bookName: String, success: Boolean, details: String = "") {
        runCatching {
            val directory = File(filesDir, "logs").apply { mkdirs() }
            val file = File(directory, FILE_NAME)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val state = if (success) "OK" else "ERROR"
            val safeDetails = details.replace('|', '/').replace('\n', ' ')
            file.appendText("$timestamp | $state | $action | $bookName | $safeDetails\n", Charsets.UTF_8)
            val lines = file.readLines(Charsets.UTF_8)
            if (lines.size > 500) file.writeText(lines.takeLast(500).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }

    fun recent(filesDir: File, limit: Int = 30): List<String> {
        val file = File(File(filesDir, "logs"), FILE_NAME)
        return runCatching { if (file.isFile) file.readLines(Charsets.UTF_8).takeLast(limit).reversed() else emptyList() }
            .getOrDefault(emptyList())
    }
}
