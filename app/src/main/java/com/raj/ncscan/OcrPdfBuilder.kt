package com.raj.ncscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object OcrPdfBuilder {

    // ponytail: konstanta preset di satu tempat; tune setelah tes device pertama
    private val PRESETS = arrayOf(1000 to 50, 1500 to 60, 2000 to 75) // long edge px to JPEG quality
    private const val OCR_LONG_EDGE = 2560
    private const val A4_LONG_PT = 842f

    /** Bangun PDF searchable dari JPEG halaman scanner. Mengembalikan jumlah kata OCR yang tertanam. */
    suspend fun build(context: Context, pageUris: List<Uri>, prefs: Prefs, outFile: File): Int {
        val (targetEdge, quality) = PRESETS[prefs.quality.coerceIn(0, PRESETS.lastIndex)]
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var words = 0
        try {
            PDDocument().use { doc ->
                for (uri in pageUris) {
                    // decode gagal = throw, bukan skip: halaman hilang diam-diam lebih buruk
                    // daripada scan gagal yang terlihat (catch di MainActivity menampilkan error)
                    val ocrBmp = decodeScaled(context, uri)
                    val w0 = ocrBmp.width
                    val h0 = ocrBmp.height
                    // OCR di bitmap resolusi penuh; bounding box dalam pixel space bitmap ini.
                    // fromBitmap (bukan fromFilePath) supaya koordinat tidak tergeser EXIF.
                    val ocr: Text? = try {
                        recognizer.process(InputImage.fromBitmap(ocrBmp, 0)).await()
                    } catch (e: CancellationException) {
                        throw e // rotasi/destroy: jangan lanjut kerja di coroutine mati
                    } catch (e: Exception) {
                        null // model belum terunduh / offline: PDF tetap dibuat tanpa text layer
                    }
                    val pageBmp = scaleAndFilter(ocrBmp, targetEdge, prefs.grayscale)
                    if (pageBmp !== ocrBmp) ocrBmp.recycle()
                    val jpeg = ByteArrayOutputStream().also {
                        pageBmp.compress(Bitmap.CompressFormat.JPEG, quality, it)
                    }.toByteArray()
                    pageBmp.recycle()

                    val k = A4_LONG_PT / max(w0, h0)
                    val pageW = w0 * k
                    val pageH = h0 * k
                    val page = PDPage(PDRectangle(pageW, pageH))
                    doc.addPage(page)
                    // JPEGFactory = DCTDecode passthrough, PDFBox tidak re-encode
                    val img = JPEGFactory.createFromStream(doc, ByteArrayInputStream(jpeg))
                    PDPageContentStream(doc, page).use { cs ->
                        cs.drawImage(img, 0f, 0f, pageW, pageH)
                        if (ocr != null) words += addTextLayer(cs, ocr, k, pageH)
                    }
                }
                // save ke .part lalu rename atomik: process death mid-save tidak boleh
                // meninggalkan .pdf korup yang ikut tersapu upload queue
                val tmp = File(outFile.parentFile, outFile.name + ".part")
                doc.save(tmp)
                if (!tmp.renameTo(outFile)) {
                    tmp.delete()
                    throw IOException("Gagal menyimpan ${outFile.name}")
                }
            }
        } finally {
            recognizer.close()
        }
        return words
    }

    // ML Kit: pixel space, origin kiri-atas, y ke bawah. PDF: point, origin kiri-bawah, y ke atas.
    private fun addTextLayer(cs: PDPageContentStream, ocr: Text, k: Float, pageH: Float): Int {
        val font = PDType1Font.HELVETICA // standard-14, tanpa embed
        var words = 0
        cs.setRenderingMode(RenderingMode.NEITHER) // text mode 3: invisible
        for (block in ocr.textBlocks) for (line in block.lines) for (el in line.elements) {
            val r = el.boundingBox ?: continue
            if (r.height() <= 0 || r.width() <= 0) continue
            val word = sanitizeWinAnsi(el.text)
            if (word.isBlank()) continue
            try {
                val fontSize = r.height() * k
                val naturalW = font.getStringWidth(word) / 1000f * fontSize
                if (naturalW <= 0f) continue
                val hScale = r.width() * k / naturalW // fit lebar via x-scale text matrix
                val baselineY = (pageH - r.bottom * k) - font.fontDescriptor.descent / 1000f * fontSize
                cs.beginText()
                try {
                    cs.setFont(font, fontSize)
                    cs.setTextMatrix(Matrix(hScale, 0f, 0f, 1f, r.left * k, baselineY))
                    cs.showText(word)
                } finally {
                    cs.endText() // BT/ET harus seimbang meski showText throw
                }
                words++
            } catch (e: Exception) {
                // ponytail: satu kata tak ter-encode tidak boleh mematikan seluruh PDF
            }
        }
        return words
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (context.contentResolver.openInputStream(uri)
            ?: throw IOException("Tidak bisa membuka halaman: $uri")).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Halaman tidak bisa di-decode: $uri")
        }
        var sample = 1
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        while (longEdge / sample > OCR_LONG_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IOException("Decode gagal: $uri")
    }

    private fun scaleAndFilter(src: Bitmap, targetLongEdge: Int, grayscale: Boolean): Bitmap {
        val scale = min(1f, targetLongEdge / max(src.width, src.height).toFloat())
        if (scale >= 1f && !grayscale) return src
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (grayscale) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        Canvas(out).drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
        return out
    }

    // Helvetica ber-encoding WinAnsi; karakter di luar itu bikin showText throw.
    // Indonesia/Inggris praktis ASCII semua, jadi nyaris nol kehilangan.
    private fun sanitizeWinAnsi(s: String): String =
        s.map { c -> if (c.code in 32..126 || c.code in 160..255) c else '?' }.joinToString("")
}
