package com.example.bookmeta
import android.net.Uri
/** Cover selection state helper. Actual embedding in EPUB/FB2 requires format-specific file rewriting. */
object CoverManager {
    fun selectedUri(uri: Uri): String = uri.toString()
}
