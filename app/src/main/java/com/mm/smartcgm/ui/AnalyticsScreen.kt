package com.mm.smartcgm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mm.smartcgm.core.AgpHour
import com.mm.smartcgm.core.DAY_MS
import com.mm.smartcgm.core.Logbook
import com.mm.smartcgm.core.Stats
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.export.Exporter

@Composable
fun AnalyticsScreen() {
    val ctx = LocalContext.current
    val key = rememberRefreshKey()
    val s by Repo.settings.collectAsState()
    var days by remember { mutableIntStateOf(14) }
    val now = System.currentTimeMillis()
    val readings = remember(key, days) { Repo.readings(now - days * DAY_MS) }
    val st = remember(readings, s.therapy.lowMgdl, s.therapy.highMgdl) { Stats.compute(readings, s.therapy.lowMgdl, s.therapy.highMgdl) }
    val agp = remember(readings) { Stats.agp(readings) }
    val coverage = remember(readings) { Stats.coveragePercent(readings, now - days * DAY_MS, now) }
    val overlays = remember(readings) { Stats.dailyOverlays(readings).takeLast(14) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "يوم", 7 to "7 أيام", 14 to "14 يوم", 30 to "30 يوم").forEach { (d, l) ->
                FilterChip(days == d, { days = d }, label = { Text(l) })
            }
        }
        if (st == null) {
            Text("📭 مفيش قراءات كفاية لسه.", Modifier.padding(16.dp)); return@Column
        }
        val u = Repo.settings.value.unit
        SectionCard("🎯 الوقت في النطاق (${u.format(s.therapy.lowMgdl)}–${u.format(s.therapy.highMgdl)})") {
            TirBar(st.veryLowPct, st.lowPct, st.inRangePct, st.highPct, st.veryHighPct)
            Text("🟢 في النطاق: %.0f%%  (الهدف أكتر من 70%%)".format(st.inRangePct), fontWeight = FontWeight.Bold)
            Text("🔴 تحت ${u.format(s.therapy.lowMgdl)}: %.1f%%  (منهم أقل من ${u.format(minOf(54.0, s.therapy.lowMgdl))}: %.1f%%) — الهدف أقل من 4%%".format(st.lowPct + st.veryLowPct, st.veryLowPct))
            Text("🟠 فوق ${u.format(s.therapy.highMgdl)}: %.0f%%  (منهم فوق ${u.format(maxOf(250.0, s.therapy.highMgdl))}: %.0f%%)".format(st.highPct + st.veryHighPct, st.veryHighPct))
        }
        SectionCard("🧪 المؤشرات") {
            Text("📊 متوسط السكر: ${u.formatWithUnit(st.meanMgdl)}")
            Text("🩸 السكر التراكمي التقديري (GMI): %.1f%%".format(st.gmiPercent))
            Text("〰️ التذبذب (CV): %.0f%%  %s".format(st.cvPercent, if (st.cvPercent <= 36) "✅ مستقر" else "⚠️ متذبذب"))
            Text("🔢 عدد القراءات: ${st.count}")
            val ok = Stats.meetsConsensus(coverage, days)
            Text("📶 البيانات المتاحة: %.0f%% من الوقت — %s".format(coverage,
                if (ok) "✅ كافية للتقرير (14 يوم و70%+)" else if (days < 14) "ℹ️ المعيار الدولي محتاج 14 يوم" else "⚠️ أقل من 70% — فسّر بحذر"),
                color = if (ok || days < 14) androidx.compose.ui.graphics.Color.Unspecified else Orange)
        }
        SectionCard("🌗 المنحنى اليومي المجمّع (AGP)") { AgpChart(agp, s.therapy.lowMgdl, s.therapy.highMgdl) }
        SectionCard("🗓️ الأيام فوق بعض") { DailyOverlayChart(overlays, s.therapy.lowMgdl, s.therapy.highMgdl) }
        SectionCard("📈 المنحنى الكامل") { GlucoseChart(readings, now - days * DAY_MS, now, s.therapy.lowMgdl, s.therapy.highMgdl) }
        OutlinedButton(onClick = {
            val rows = Logbook.hourly(readings, now - days * DAY_MS, now, s.therapy.lowMgdl, s.therapy.highMgdl,
                events = Repo.logEvents(now - days * DAY_MS))
            Exporter.share(ctx, Exporter.pdf(ctx, rows, st, "تقرير السكر للطبيب — آخر $days يوم"), "application/pdf")
        }, Modifier.padding(12.dp)) { Text("🩺 صدّر تقرير كامل للدكتور (PDF)") }
        OutlinedButton(onClick = {
            Exporter.share(ctx, Exporter.agpPdf(ctx, st, agp, coverage, days, s.therapy.lowMgdl, s.therapy.highMgdl), "application/pdf")
        }, Modifier.padding(horizontal = 12.dp)) { Text("📊 تقرير AGP بالرسم للدكتور (PDF)") }
    }
}

@Composable
private fun TirBar(vlow: Double, low: Double, inr: Double, high: Double, vhigh: Double) {
    Canvas(Modifier.fillMaxWidth().height(28.dp).padding(vertical = 4.dp)) {
        var x = size.width // right-to-left: lows on the right like the Arabic reading order
        listOf(vlow to Color(0xFF8E0000), low to Red, inr to Green, high to Orange, vhigh to Color(0xFFBF360C)).forEach { (p, c) ->
            val w = (size.width * p / 100).toFloat()
            drawRect(c, Offset(x - w, 0f), Size(w, size.height))
            x -= w
        }
    }
}

@Composable
private fun AgpChart(agp: List<AgpHour>, low: Double, high: Double) {
    Canvas(Modifier.fillMaxWidth().height(200.dp).padding(8.dp)) {
        if (agp.size < 2) return@Canvas
        fun x(h: Int) = size.width * (1 - h / 23f)
        fun y(v: Double) = (size.height * (1 - (v.coerceIn(40.0, 300.0) - 40) / 260)).toFloat()
        drawRect(Color(0x2266BB6A), Offset(0f, y(high)), Size(size.width, y(low) - y(high)))
        fun band(lo: (AgpHour) -> Double, hi: (AgpHour) -> Double, c: Color) {
            val p = Path()
            agp.forEachIndexed { i, a -> if (i == 0) p.moveTo(x(a.hour), y(hi(a))) else p.lineTo(x(a.hour), y(hi(a))) }
            agp.reversed().forEach { a -> p.lineTo(x(a.hour), y(lo(a))) }
            p.close(); drawPath(p, c)
        }
        band({ it.p5 }, { it.p95 }, Color(0x3342A5F5))
        band({ it.p25 }, { it.p75 }, Color(0x661E88E5))
        val med = Path()
        agp.forEachIndexed { i, a -> if (i == 0) med.moveTo(x(a.hour), y(a.p50)) else med.lineTo(x(a.hour), y(a.p50)) }
        drawPath(med, Color(0xFF0D47A1), style = Stroke(4f))
    }
    Text("الخط = الوسيط • الغامق = نص القراءات • الفاتح = 90% من القراءات • من 12ص (يمين) لـ 11م (شمال)")
}
