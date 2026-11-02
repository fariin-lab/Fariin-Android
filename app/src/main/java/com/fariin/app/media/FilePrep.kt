package com.fariin.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A picked document (iOS sendFile): its display name, its bytes, and for a PDF the first page
 * drawn 300 px on the long side on white (iOS documentPreviewJPEG).
 */
class FilePrep(val name: String, val bytes: ByteArray, val previewJpeg: ByteArray?) {
    companion object {
        /** Limits.fileUploadBytes: 25 MB, deliberately below the Storage rule. */
        const val MAX_BYTES = 25 * 1024 * 1024

        sealed class Result {
            data class Ok(val prep: FilePrep) : Result()
            object TooLarge : Result()
            object Unreadable : Result()
        }

        fun fromUri(context: Context, uri: Uri, scratch: File): Result {
            val cr = context.contentResolver
            var name = "File"
            var size = -1L
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            if (size > MAX_BYTES) return Result.TooLarge
            val bytes = runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return Result.Unreadable
            if (bytes.size > MAX_BYTES) return Result.TooLarge
            return Result.Ok(FilePrep(MediaRules.safeFileName(name), bytes, pdfPreview(name, bytes, scratch)))
        }

        fun pdfPreview(name: String, bytes: ByteArray, scratch: File): ByteArray? {
            if (!name.lowercase().endsWith(".pdf")) return null
            val tmp = File(scratch, "pdf-${System.nanoTime()}.pdf")
            return try {
                tmp.writeBytes(bytes)
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        if (r.pageCount == 0) return null
                        r.openPage(0).use { p ->
                            if (p.width <= 0 || p.height <= 0) return null
                            val scale = 300f / maxOf(p.width, p.height)
                            val bmp = Bitmap.createBitmap(maxOf(1, (p.width * scale).toInt()), maxOf(1, (p.height * scale).toInt()),
                                Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                        }
                    }
                }
            } catch (_: Exception) {
                null
            } finally {
                tmp.delete()
            }
        }
    }
}
