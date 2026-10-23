package com.mm.smartcgm.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.mm.smartcgm.core.GlucoseStats
import com.mm.smartcgm.core.LogRow
import com.mm.smartcgm.core.Logbook
import com.mm.smartcgm.core.RangeColor
import com.mm.smartcgm.data.Repo
import java.io.File

/** One-tap export of the hourly table (Excel-compatible CSV or colored PDF) and the doctor report. */
object Exporter {

    private fun file(ctx: Context, name: String) = File(ctx.cacheDir, "exports").apply { mkdirs() }.let { File(it, name) }

    fun share(ctx: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "مشاركة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun csv(ctx: Context, rows: List<LogRow>): File =
        file(ctx, "سجل_السكر.csv").apply { writeText(Logbook.toCsv(rows, Repo.settings.value.unit), Charsets.UTF_8) }

    private fun colorOf(c: RangeColor?) = when (c) {
        RangeColor.GREEN -> Color.rgb(200, 230, 201)
        RangeColor.RED -> Color.rgb(255, 205, 210)
        RangeColor.ORANGE -> Color.rgb(255, 224, 178)
        null -> Color.WHITE
    }

    /** A4 PDF, right-to-left columns: اليوم | التاريخ | التوقيت | نسبة السكر. */
    fun pdf(ctx: Context, rows: List<LogRow>, stats: GlucoseStats?, title: String): File {
        val doc = PdfDocument()
        val w = 595
        val h = 842
        val text = Paint().apply { textSize = 11f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val bold = Paint(text).apply { isFakeBoldText = true; textSize = 12f }
        val fill = Paint()
        val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
        val cols = listOf("اليوم", "التاريخ", "التوقيت", "نسبة السكر")
        val colW = (w - 80) / 4f
        fun colX(i: Int) = w - 40 - colW * i // right-to-left
        val rowH = 18f
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var y = 50f

        fun header() {
            val c = page.canvas
            c.drawText(title, w / 2f, y, bold.apply { textSize = 15f }); bold.textSize = 12f
            y += 22
            if (pageNo == 1 && stats != null) {
                c.drawText("في النطاق %.0f%%  •  تحت %.0f%%  •  فوق %.0f%%  •  المتوسط %s  •  GMI %.1f%%".format(
                    stats.inRangePct, stats.lowPct + stats.veryLowPct, stats.highPct + stats.veryHighPct,
                    Repo.settings.value.unit.formatWithUnit(stats.meanMgdl), stats.gmiPercent), w / 2f, y, text)
                y += 22
            }
            cols.forEachIndexed { i, s -> c.drawText(s, colX(i) - colW / 2, y, bold) }
            y += 8
            c.drawLine(40f, y, w - 40f, y, line)
        }
        header()
        for (r in rows) {
            if (y + rowH > h - 40) {
                doc.finishPage(page); pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create()); y = 50f; header()
            }
            val c = page.canvas
            fill.color = colorOf(r.color)
            c.drawRect(40f, y, w - 40f, y + rowH, fill)
            val vals = listOf(r.dayName, r.date, r.time, Logbook.value(r, Repo.settings.value.unit).ifEmpty { "—" })
            vals.forEachIndexed { i, s -> c.drawText(s, colX(i) - colW / 2, y + 13, text) }
            y += rowH
            c.drawLine(40f, y, w - 40f, y, line)
        }
        doc.finishPage(page)
        val f = file(ctx, "تقرير_السكر.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
