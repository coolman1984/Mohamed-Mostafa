package com.mm.smartcgm.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import com.mm.smartcgm.core.AgpHour
import com.mm.smartcgm.core.Stats
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

    /** Shortens text with "…" so it fits a PDF column. */
    private fun fit(s: String, width: Float, p: Paint): String {
        if (p.measureText(s) <= width) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    /**
     * P2-4: AGP chart drawn with android.graphics into a Bitmap (same look as the screen) so it can go into the PDF.
     * Time axis right→left (12 AM on the right) like the rest of the app.
     */
    fun agpBitmap(agp: List<AgpHour>, low: Double, high: Double, wPx: Int = 1030, hPx: Int = 420): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val padL = 50f; val padB = 34f; val pw = wPx - padL - 10f; val ph = hPx - padB - 10f
        fun x(h: Int) = padL + pw * (1 - h / 23f)
        fun y(v: Double) = 10f + (ph * (1 - (v.coerceIn(40.0, 300.0) - 40) / 260)).toFloat()
        c.drawRect(padL, y(high), padL + pw, y(low), Paint().apply { color = Color.argb(40, 102, 187, 106) })
        val axis = Paint().apply { color = Color.DKGRAY; textSize = 18f; isAntiAlias = true }
        listOf(54.0, low, high, 250.0).forEach { v ->
            c.drawLine(padL, y(v), padL + pw, y(v), Paint().apply { color = Color.LTGRAY; strokeWidth = 1f })
            c.drawText(Repo.settings.value.unit.format(v), 2f, y(v) + 6, axis)
        }
        listOf(0, 6, 12, 18, 23).forEach { h -> c.drawText(Logbook.hour12(java.time.ZonedDateTime.now().withHour(h)), (x(h) - 18).coerceIn(padL, wPx - 46f), hPx - 8f, axis) }
        if (agp.size >= 2) {
            fun band(lo: (AgpHour) -> Double, hi: (AgpHour) -> Double, col: Int) {
                val p = Path()
                agp.forEachIndexed { i, a -> if (i == 0) p.moveTo(x(a.hour), y(hi(a))) else p.lineTo(x(a.hour), y(hi(a))) }
                agp.reversed().forEach { a -> p.lineTo(x(a.hour), y(lo(a))) }
                p.close(); c.drawPath(p, Paint().apply { color = col; isAntiAlias = true })
            }
            band({ it.p5 }, { it.p95 }, Color.argb(60, 66, 165, 245))
            band({ it.p25 }, { it.p75 }, Color.argb(110, 30, 136, 229))
            val med = Path()
            agp.forEachIndexed { i, a -> if (i == 0) med.moveTo(x(a.hour), y(a.p50)) else med.lineTo(x(a.hour), y(a.p50)) }
            c.drawPath(med, Paint().apply { color = Color.rgb(13, 71, 161); style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true })
        }
        return bmp
    }

    /** P2-4: one-page doctor report — key numbers, 14-day data-sufficiency check and the AGP image. */
    fun agpPdf(ctx: Context, stats: GlucoseStats, agp: List<AgpHour>, coveragePct: Double, days: Int, low: Double, high: Double): File {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        drawAgpReport(page.canvas, stats, agp, coveragePct, days, low, high)
        doc.finishPage(page)
        val f = file(ctx, "تقرير_AGP.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /** Draws the A4 (595×842 pt) AGP report page; separate from PdfDocument so it can be tested on a Bitmap. */
    fun drawAgpReport(c: Canvas, stats: GlucoseStats, agp: List<AgpHour>, coveragePct: Double, days: Int, low: Double, high: Double) {
        val w = 595
        val u = Repo.settings.value.unit
        val t = Paint().apply { textSize = 12f; isAntiAlias = true; textAlign = Paint.Align.RIGHT }
        val b = Paint(t).apply { isFakeBoldText = true; textSize = 16f }
        var y = 50f
        fun line(s: String, p: Paint = t) { c.drawText(s, w - 40f, y, p); y += p.textSize + 8 }
        line("تقرير AGP للطبيب — آخر $days يوم", b)
        // One fact per line: mixing Arabic, numbers and Latin on one canvas line scrambles the bidi order.
        val ok = Stats.meetsConsensus(coveragePct, days)
        listOf(
            "الوقت في النطاق: %.0f%%".format(stats.inRangePct),
            "الوقت تحت النطاق: %.1f%%".format(stats.lowPct + stats.veryLowPct),
            "منه هبوط شديد: %.1f%%".format(stats.veryLowPct),
            "الوقت فوق النطاق: %.0f%%".format(stats.highPct + stats.veryHighPct),
            "منه ارتفاع شديد: %.0f%%".format(stats.veryHighPct),
            "متوسط السكر: ${u.format(stats.meanMgdl)} ${u.label}",
            "السكر التراكمي التقديري GMI: %.1f%%".format(stats.gmiPercent),
            "التذبذب CV: %.0f%%".format(stats.cvPercent),
            "البيانات المتاحة: %.0f%% من الوقت".format(coveragePct),
            if (ok) "✓ البيانات كافية حسب المعيار الدولي" else "⚠ البيانات أقل من المعيار الدولي — فسّر بحذر",
            "النطاق: ${u.format(low)} – ${u.format(high)} ${u.label}",
        ).forEach { line(it) }
        y += 6
        val bmp = agpBitmap(agp, low, high)
        val dst = android.graphics.RectF(40f, y, w - 40f, y + (w - 80f) * bmp.height / bmp.width)
        c.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        y = dst.bottom + 18
        line("الخط = الوسيط • الغامق = 25–75% • الفاتح = 5–95%")
        line("⚕ تقرير مساعد — مش بديل لتقييم الطبيب.")
    }

    /** A4 PDF, right-to-left columns: اليوم | التاريخ | التوقيت | نسبة السكر. */
    fun pdf(ctx: Context, rows: List<LogRow>, stats: GlucoseStats?, title: String): File {
        val doc = PdfDocument()
        val w = 595
        val h = 842
        val text = Paint().apply { textSize = 11f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val bold = Paint(text).apply { isFakeBoldText = true; textSize = 12f }
        val fill = Paint()
        val small = Paint(text).apply { textSize = 8f }
        val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
        val cols = listOf("اليوم", "التاريخ", "التوقيت", "نسبة السكر", "الأحداث")
        val colW = (w - 80) / 5f
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
            val vals = listOf(r.dayName, r.date, r.time, Logbook.value(r, Repo.settings.value.unit).ifEmpty { "—" },
                fit(r.events, colW - 4, small))
            vals.forEachIndexed { i, s -> c.drawText(s, colX(i) - colW / 2, y + 13, if (i == 4) small else text) }
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
