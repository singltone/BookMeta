
package com.example.bookmeta

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object CoverManager {

    /** Downloads a remote cover and normalizes it to JPEG bytes. */
    fun downloadAsJpeg(url: String, maxSize: Int = 2400, quality: Int = 92): ByteArray? {
        if (url.isBlank()) return null
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "BookMeta/1.0")
            setRequestProperty("Accept", "image/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 12 * 1024 * 1024) error("Зображення завелике")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bitmap = decodeBytes(bytes, maxSize) ?: return null
            return try {
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), output)
                    output.toByteArray()
                }
            } finally { bitmap.recycle() }
        } finally { connection.disconnect() }
    }

    private fun decodeBytes(bytes: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSize * 2 || bounds.outHeight / sample > maxSize * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    /**
     * Reads an image selected by the user and converts it to JPEG.
     * Returns null if the image cannot be decoded.
     */
    fun readAsJpegBytes(
        resolver: ContentResolver,
        uri: Uri,
        maxSize: Int = 2400,
        quality: Int = 92
    ): ByteArray? {
        val bitmap = decode(resolver, uri, maxSize) ?: return null

        return try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    quality.coerceIn(50, 100),
                    output
                )
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Creates a preview bitmap for the editor UI.
     * The caller should recycle it when it is no longer needed.
     */
    fun createPreview(
        resolver: ContentResolver,
        uri: Uri,
        maxSize: Int = 700
    ): Bitmap? = decode(resolver, uri, maxSize)

    /**
     * Checks whether the selected URI contains a decodable image.
     */
    fun isValidImage(
        resolver: ContentResolver,
        uri: Uri
    ): Boolean {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        return try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            options.outWidth > 0 && options.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun decode(
        resolver: ContentResolver,
        uri: Uri,
        maxSize: Int
    ): Bitmap? {
        if (maxSize < 1) return null

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null
        } catch (_: Exception) {
            return null
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / sample > maxSize * 2 ||
            bounds.outHeight / sample > maxSize * 2
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        return try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }
}
