package com.forja.app.feature.cleanup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Xml
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Content-only evidence for protocol 4. Never uploads, classifies, or modifies an original. */
internal object OrganizerContentEvidence {
    private const val MAX_BYTES = 25L * 1024 * 1024
    private const val MAX_TEXT = 32000
    private const val MAX_PAGES = 40
    private const val MAX_OCR_PAGES = 12

    suspend fun extractEvidence(context: Context, file: CleanFile, sha: String): JSONObject = withContext(Dispatchers.IO) {
        require(sha.matches(Regex("[a-f0-9]{64}"))) { "Amprenta originalului este invalidă." }
        val scratch = File.createTempFile("organizer-evidence-", ".part", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            context.contentResolver.openInputStream(Uri.parse(file.uri))?.use { input ->
                scratch.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= MAX_BYTES) { "Fișierul depășește limita de analiză." }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("Originalul nu mai este accesibil.")
            require(count > 0 && (file.bytes <= 0 || count == file.bytes)) { "Originalul s-a schimbat." }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(actual == sha) { "Originalul s-a schimbat după încărcare." }
            currentCoroutineContext().ensureActive()
            val reader = Reader(context, scratch, sha)
            try { reader.read(file.mime) } finally { reader.close() }
        } finally { scratch.delete() }
    }

    private class Reader(private val context: Context, private val file: File, private val sha: String) {
        private val started = SystemClock.elapsedRealtime()
        private var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
        private val text = StringBuilder()
        private val spans = JSONArray()
        private val limitations = linkedSetOf<String>()
        private var partial = false
        private var totalPages: Int? = null
        private var processedPages: Int? = null

        private suspend fun check() {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() - started < 120000L) { "content_time_limit" }
        }

        suspend fun read(mime: String): JSONObject {
            val prefix = file.inputStream().use { input -> ByteArray(1024).let { b -> b.copyOf(input.read(b).coerceAtLeast(0)) } }
            var method = "unsupported"
            try {
                when {
                    prefix.toString(Charsets.ISO_8859_1).contains("%PDF-") -> { method = "pdf_text"; pdf(); if (spansContainOcr()) method = "pdf_text_ocr" }
                    imageSignature(prefix) -> { method = "image_ocr"; image() }
                    prefix.size >= 4 && prefix[0] == 80.toByte() && prefix[1] == 75.toByte() && prefix[2] == 3.toByte() && prefix[3] == 4.toByte() -> { method = "office_xml"; office() }
                    mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/octet-stream") -> { method = "utf8"; utf8() }
                    else -> { partial = true; limitations += "format_not_supported" }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { partial = true; limitations += "content_extraction_incomplete" }
            if (method == "pdf_text" && spansContainOcr()) method = "pdf_text_ocr"
            check()
            if (text.isBlank()) { partial = true; method = "unsupported"; limitations += "no_readable_text" }
            return JSONObject().put("source_sha256", sha).put("method", method).put("text", text.toString())
                .put("partial", partial).put("page_spans", spans).put("limitations", JSONArray(limitations.take(10))).apply {
                    totalPages?.let { put("pages_total", it) }; processedPages?.let { put("pages_processed", it) }
                }
        }

        private fun spansContainOcr(): Boolean = (0 until spans.length()).any { spans.getJSONObject(it).optString("method") == "ocr" }
        private fun append(value: String, page: Int? = null, method: String = "pdf_text") {
            val cleaned = value.replace('\u0000', ' ').trim()
            if (cleaned.isEmpty()) return
            if (text.isNotEmpty() && text.length < MAX_TEXT) text.append('\n')
            val room = MAX_TEXT - text.length
            if (cleaned.length > room) { partial = true; limitations += "text_limit" }
            val start = text.length
            text.append(cleaned.take(room.coerceAtLeast(0)))
            if (page != null && text.length > start) spans.put(JSONObject().put("page", page).put("start", start).put("end", text.length).put("method", method))
        }

        private suspend fun utf8() {
            check()
            val data = file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() < 128000) {
                    check(); val n = input.read(buffer, 0, minOf(buffer.size, 128000 - output.size()))
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            val truncated = file.length() > data.size
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val output = java.nio.CharBuffer.allocate(128000)
            val result = decoder.decode(ByteBuffer.wrap(data), output, !truncated)
            if (result.isError) result.throwException()
            output.flip()
            val value = output.toString().removePrefix("\uFEFF")
            require(!Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]").containsMatchIn(value)) { "binary_content" }
            if (truncated) { partial = true; limitations += "text_limit" }
            append(value)
        }

        private suspend fun ocr(bitmap: Bitmap, rotation: Int = 0): String {
            check()
            val client = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            // Await native completion before recycling its bitmap, even if the job is cancelled.
            val value = withContext(NonCancellable) { client.process(InputImage.fromBitmap(bitmap, rotation)).await().text }
            check()
            return value
        }

        private suspend fun image() {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "image_decode_failed" }
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1800) options.inSampleSize *= 2
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: error("image_decode_failed")
            try {
                val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val rotation = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0 }
                append(ocr(bitmap, rotation))
                partial = true; limitations += "ocr_does_not_describe_scene"; limitations += "ocr_quality_unverified"
                if (options.inSampleSize > 1) limitations += "ocr_reduced_resolution"
                if (orientation in setOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_FLIP_VERTICAL, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)) limitations += "mirrored_orientation_not_corrected"
            } finally { bitmap.recycle() }
        }

        private suspend fun pdf() {
            PDFBoxResourceLoader.init(context)
            PDDocument.load(file).use { document ->
                require(!document.isEncrypted || document.currentAccessPermission.canExtractContent()) { "pdf_protected" }
                totalPages = document.numberOfPages
                require(totalPages!! in 1..100000) { "pdf_page_limit" }
                processedPages = 0
                val chosen = if (totalPages!! <= MAX_PAGES) (0 until totalPages!!).toList() else (0 until MAX_PAGES).map { it * (totalPages!! - 1) / (MAX_PAGES - 1) }.distinct()
                if (chosen.size < totalPages!!) { partial = true; limitations += "page_limit" }
                // Text/OCR evidence does not establish complete understanding of diagrams or layout.
                partial = true; limitations += "pdf_visual_layout_not_analyzed"
                var renderer: PdfRenderer? = null
                var ocrPages = 0
                try {
                    for (index in chosen) {
                        check()
                        if (text.length >= MAX_TEXT) { limitations += "text_limit"; break }
                        var pageText = runCatching { PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(document) }.getOrDefault("")
                        var method = "pdf_text"
                        if (pageText.trim().length < 40) {
                            if (ocrPages < MAX_OCR_PAGES) {
                                ocrPages++
                                try {
                                    if (renderer == null) renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                                    renderer!!.openPage(index).use { page ->
                                        val scale = 1600.0 / maxOf(page.width, page.height).coerceAtLeast(1)
                                        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                        try {
                                            bitmap.eraseColor(Color.WHITE)
                                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            val recognized = ocr(bitmap)
                                            if (recognized.trim().length > pageText.trim().length) { pageText = recognized; method = "ocr" }
                                            limitations += "ocr_quality_unverified"
                                        } finally { bitmap.recycle() }
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { limitations += "page_ocr_unavailable" }
                            } else limitations += "ocr_page_limit"
                        }
                        append(pageText, index + 1, method)
                        processedPages = processedPages!! + 1
                    }
                } finally { renderer?.close() }
                if (processedPages != totalPages) partial = true
            }
        }

        private suspend fun office() {
            partial = true
            limitations += "office_layout_images_formulas_not_analyzed"
            var inflated = 0L
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory && (it.name in setOf("word/document.xml", "content.xml", "xl/sharedStrings.xml") || it.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml|xl/worksheets/sheet[0-9]+\\.xml"))) }
                    .take(101).toList().sortedWith(compareBy({ it.name.substringBeforeLast('/') }, { Regex("[0-9]+").find(it.name.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 }))
                if (entries.size > 100) limitations += "office_part_limit"
                if (entries.isEmpty()) { limitations += "office_content_unavailable"; return }
                for (entry in entries.take(100)) {
                    check()
                    if (text.length >= MAX_TEXT) { limitations += "text_limit"; break }
                    val data = zip.getInputStream(entry).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            check(); val n = input.read(buffer); if (n < 0) break
                            inflated += n
                            require(inflated <= 12L * 1024 * 1024 && output.size() + n <= 2 * 1024 * 1024) { "office_xml_limit" }
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    }
                    val xml = data.toString(Charsets.UTF_8)
                    require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "external_xml_declaration" }
                    val parser = Xml.newPullParser()
                    parser.setInput(xml.reader())
                    val tags = ArrayDeque<String>()
                    var sharedStringCell = false
                    var event = parser.eventType
                    val extracted = StringBuilder()
                    while (event != XmlPullParser.END_DOCUMENT) {
                        check()
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                val tag = parser.name.substringAfter(':'); tags.addLast(tag)
                                if (tag == "c") sharedStringCell = parser.getAttributeValue(null, "t") == "s"
                            }
                            XmlPullParser.END_TAG -> { if (tags.lastOrNull() == "c") sharedStringCell = false; if (tags.isNotEmpty()) tags.removeLast() }
                            XmlPullParser.TEXT -> {
                                val tag = tags.lastOrNull()
                                if (tag in setOf("t", "p", "span", "h", "a") || tag == "v" && !sharedStringCell) {
                                    extracted.append(parser.text).append(' ')
                                    if (extracted.length + text.length >= MAX_TEXT) { limitations += "text_limit"; break }
                                }
                            }
                        }
                        event = parser.next()
                    }
                    if (entry.name.startsWith("xl/")) limitations += "spreadsheet_cell_order_partial"
                    append(extracted.toString())
                }
            }
        }

        fun close() { recognizer?.close() }
    }

    private fun imageSignature(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) ||
        bytes.size >= 3 && bytes[0] == 255.toByte() && bytes[1] == 216.toByte() && bytes[2] == 255.toByte() ||
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
}
