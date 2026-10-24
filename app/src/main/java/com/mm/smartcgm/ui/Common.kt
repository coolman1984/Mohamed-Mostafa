package com.mm.smartcgm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mm.smartcgm.core.GlucoseReading
import com.mm.smartcgm.core.RangeColor
import com.mm.smartcgm.data.Repo
import kotlinx.coroutines.delay

val Green = Color(0xFF2E7D32)
val Red = Color(0xFFC62828)
val Orange = Color(0xFFEF6C00)

fun colorFor(mgdl: Double, low: Double, high: Double) = when {
    mgdl < low -> Red
    mgdl > high -> Orange
    else -> Green
}

fun rangeColor(c: RangeColor?) = when (c) {
    RangeColor.GREEN -> Color(0xFFC8E6C9)
    RangeColor.RED -> Color(0xFFFFCDD2)
    RangeColor.ORANGE -> Color(0xFFFFE0B2)
    null -> Color.Transparent
}

/** Recomposes on every data change and at least every 30 s (for "x minutes ago"). */
@Composable
fun rememberRefreshKey(): Long {
    val changes by Repo.changes.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    return changes + tick
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** Glucose line over a shaded target band. Time runs right→left to match Arabic reading. */
@Composable
fun GlucoseChart(readings: List<GlucoseReading>, fromMs: Long, toMs: Long, low: Double, high: Double, heightDp: Int = 200) {
    Canvas(Modifier.fillMaxWidth().height(heightDp.dp).padding(8.dp)) {
        val maxY = 300.0
        val minY = 40.0
        fun y(v: Double) = (size.height * (1 - (v.coerceIn(minY, maxY) - minY) / (maxY - minY))).toFloat()
        fun x(t: Long) = (size.width * (1 - (t - fromMs).toDouble() / (toMs - fromMs))).toFloat()
        drawRect(Color(0x2266BB6A), topLeft = Offset(0f, y(high)), size = androidx.compose.ui.geometry.Size(size.width, y(low) - y(high)))
        listOf(low, high).forEach { drawLine(Color(0x55000000), Offset(0f, y(it)), Offset(size.width, y(it)), 1f) }
        if (readings.size < 2) return@Canvas
        val path = Path()
        readings.forEachIndexed { i, r ->
            if (i == 0) path.moveTo(x(r.timeMs), y(r.mgdl)) else path.lineTo(x(r.timeMs), y(r.mgdl))
        }
        drawPath(path, Color(0xFF37474F), style = Stroke(3f))
        readings.last().let { drawCircle(colorFor(it.mgdl, low, high), 8f, Offset(x(it.timeMs), y(it.mgdl))) }
    }
}

/** Small dialog asking for one number. */
@Composable
fun NumberDialog(title: String, label: String, onDismiss: () -> Unit, onOk: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { com.mm.smartcgm.core.UserNumber.parse(text)?.let { onOk(it); onDismiss() } }) { Text("حفظ ✅") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
