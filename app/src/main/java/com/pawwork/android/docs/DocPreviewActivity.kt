package com.pawwork.android.docs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.zip.ZipFile

class DocPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filePath = intent.getStringExtra("filePath") ?: run { finish(); return }
        val file = File(filePath)
        val ext = file.extension.lowercase()

        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            textSize = 14f
            text = when (ext) {
                "docx" -> previewDocx(file)
                "xlsx" -> previewXlsx(file)
                "pptx" -> previewPptx(file)
                else -> file.readText().take(10000)
            }
        }

        if (ext == "pdf") {
            val imageView = ImageView(this)
            renderPdfToBitmap(file, imageView)
            scrollView.addView(imageView)
        } else {
            scrollView.addView(textView)
        }
        setContentView(scrollView)
        title = file.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun previewDocx(file: File): String {
        return try {
            val zip = ZipFile(file)
            val doc = zip.getEntry("word/document.xml") ?: return "Cannot read docx"
            val xml = zip.getInputStream(doc).bufferedReader().readText()
            xml.replace(Regex("<[^>]+>"), "").replace("&amp;", "&")
                .replace(Regex("\\n\\s*\\n"), "\n\n").trim()
        } catch (e: Exception) { "Preview error: ${e.message}" }
    }

    private fun previewXlsx(file: File): String {
        return try {
            val zip = ZipFile(file)
            val shared = zip.getEntry("xl/sharedStrings.xml")
            val sharedStrings = if (shared != null) {
                val xml = zip.getInputStream(shared).bufferedReader().readText()
                Regex("<t[^>]*>([^<]+)</t>").findAll(xml).map { it.groupValues[1] }.toList()
            } else emptyList()

            val sheet = zip.getEntry("xl/worksheets/sheet1.xml") ?: return "No sheet found"
            val xml = zip.getInputStream(sheet).bufferedReader().readText()
            val rows = StringBuilder("📊 XLSX Preview\n\n")
            val rowMatches = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            for (rowMatch in rowMatches) {
                val cellMatches = Regex("""<c[^>]*(?:t="s")?[^>]*><v>(\d+)</v></c>""").findAll(rowMatch.value)
                val cellValues = cellMatches.map {
                    val idx = it.groupValues[1].toIntOrNull() ?: -1
                    if (idx >= 0 && idx < sharedStrings.size) sharedStrings[idx] else it.groupValues[1]
                }.toMutableList()
                rows.appendLine(cellValues.joinToString(" | "))
            }
            rows.toString()
        } catch (e: Exception) { "Preview error: ${e.message}" }
    }

    private fun previewPptx(file: File): String {
        return try {
            val zip = ZipFile(file)
            val entries = zip.entries().toList().filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            val sb = StringBuilder("📑 PPTX Presentation\n${entries.size} slide(s)\n\n")
            for ((i, entry) in entries.withIndex()) {
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                val text = xml.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim()
                sb.appendLine("── Slide ${i + 1} ──")
                sb.appendLine(text)
                sb.appendLine()
            }
            sb.toString()
        } catch (e: Exception) { "Preview error: ${e.message}" }
    }

    private fun renderPdfToBitmap(file: File, imageView: ImageView) {
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                imageView.setImageBitmap(bmp)
                imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            val tv = TextView(this).apply { text = "PDF preview error: ${e.message}"; setPadding(32, 32, 32, 32) }
            (imageView.parent as? android.view.ViewGroup)?.addView(tv)
        }
    }
}