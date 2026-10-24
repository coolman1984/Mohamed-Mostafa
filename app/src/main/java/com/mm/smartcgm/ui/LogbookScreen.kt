package com.mm.smartcgm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mm.smartcgm.core.DAY_MS
import com.mm.smartcgm.core.Logbook
import com.mm.smartcgm.core.Stats
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.export.Exporter

@Composable
fun LogbookScreen() {
    val ctx = LocalContext.current
    val key = rememberRefreshKey()
    val s by Repo.settings.collectAsState()
    var days by remember { mutableIntStateOf(1) }
    val now = System.currentTimeMillis()
    val from = now - days * DAY_MS
    val readings = remember(key, days) { Repo.readings(from - 3_600_000) }
    val rows = remember(key, days) {
        Logbook.hourly(readings, from, now, s.therapy.lowMgdl, s.therapy.highMgdl, events = Repo.logEvents(from - 3_600_000))
    }

    Column {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "يوم", 7 to "أسبوع", 14 to "أسبوعين", 30 to "شهر").forEach { (d, l) ->
                FilterChip(days == d, { days = d }, label = { Text(l) })
            }
        }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Exporter.share(ctx, Exporter.csv(ctx, rows), "text/csv") }) { Text("📊 Excel") }
            OutlinedButton(onClick = {
                val f = Exporter.pdf(ctx, rows, Stats.compute(readings.filter { it.timeMs >= from }, s.therapy.lowMgdl, s.therapy.highMgdl),
                    "جدول قياسات السكر")
                Exporter.share(ctx, f, "application/pdf")
            }) { Text("📄 PDF") }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            listOf("اليوم", "التاريخ", "التوقيت", "نسبة السكر", "الأحداث").forEachIndexed { i, it ->
                Text(it, Modifier.weight(if (i == 4) 1.6f else 1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        HorizontalDivider()
        LazyColumn {
            items(rows, key = { it.hourStartMs }) { r ->
                Row(Modifier.fillMaxWidth().background(rangeColor(r.color)).padding(vertical = 6.dp)) {
                    listOf(r.dayName, r.date, r.time, Logbook.value(r, s.unit).ifEmpty { "—" }, r.events).forEachIndexed { i, it ->
                        Text(it, Modifier.weight(if (i == 4) 1.6f else 1f), textAlign = TextAlign.Center,
                            fontSize = if (i == 4) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
