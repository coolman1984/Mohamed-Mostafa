package com.mm.smartcgm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mm.smartcgm.cloud.CloudSync
import com.mm.smartcgm.core.Calibration
import com.mm.smartcgm.core.DAY_MS
import com.mm.smartcgm.core.SensorLife
import com.mm.smartcgm.core.SensorPhase
import com.mm.smartcgm.core.SensorStatus
import com.mm.smartcgm.core.ThresholdProposal
import com.mm.smartcgm.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** P2-5: "days left" ring on Home. Green → orange (≤ 3 days) → red (past day 15). */
@Composable
fun SensorRing(st: SensorStatus) {
    val frac = SensorLife.ringFraction(st)
    val c = when {
        st.phase == SensorPhase.EXTENDED || st.phase == SensorPhase.EXPIRED -> Red
        st.daysLeftOfficial <= 3 -> Orange
        else -> Green
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(72.dp)) {
                val sw = 9.dp.toPx()
                drawArc(Color(0x22000000), 0f, 360f, false, style = Stroke(sw))
                drawArc(c, -90f, 360f * frac, false, style = Stroke(sw, cap = StrokeCap.Round))
            }
            val left = if (st.daysLeftOfficial > 0) "%.0f".format(Math.ceil(st.daysLeftOfficial)) else "0"
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(left, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c)
                Text("يوم", fontSize = 11.sp)
            }
        }
        Text("🩹 ${st.message}", Modifier.weight(1f), color = if (c == Red) Red else Color.Unspecified)
    }
}

/** P2-5: shown after a >2 h gap; the clock only restarts if the user says yes. */
@Composable
fun NewSensorPrompt() {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
        Column(Modifier.padding(14.dp)) {
            Text("🩹 القراءات رجعت بعد انقطاع أكتر من ساعتين.", fontWeight = FontWeight.Bold)
            Text("ركّبت حساس جديد؟ لو أيوه هنبدأ عدّ الـ15 يوم من أول قراءة بعد الانقطاع.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Repo.answerNewSensor(true) }) { Text("✅ أيوه حساس جديد") }
                OutlinedButton(onClick = { Repo.answerNewSensor(false) }) { Text("لا، نفس الحساس") }
            }
        }
    }
}

/** P2-6 (patient side): a follower's proposal changes nothing until approved here. */
@Composable
fun ProposalCard(p: ThresholdProposal) {
    val u = Repo.settings.value.unit
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
        Column(Modifier.padding(14.dp)) {
            Text("👨‍👩‍👧 المراقب الرئيسي بيقترح تغيير حدود التنبيه", fontWeight = FontWeight.Bold)
            Text("🔻 الانخفاض: ${u.formatWithUnit(p.lowMgdl)}   🔺 الارتفاع: ${u.formatWithUnit(p.highMgdl)}")
            Text("حد الهبوط الخطير مش بيتغير من بعيد.", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Repo.decideProposal(true) }) { Text("✅ موافق") }
                OutlinedButton(onClick = { Repo.decideProposal(false) }) { Text("❌ رفض") }
            }
        }
    }
}

/** P2-1: asks before saving a finger-stick that is > 40% away from the sensor. */
@Composable
fun StickWarningDialog(sensorMgdl: Double, meterMgdl: Double, onSave: () -> Unit, onCancel: () -> Unit) {
    val u = Repo.settings.value.unit
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("⚠️ الفرق كبير بين الوخزة والحساس") },
        text = {
            Text("الحساس: ${u.formatWithUnit(sensorMgdl)} • الجهاز: ${u.formatWithUnit(meterMgdl)} (فرق %.0f%%).\n".format(
                Calibration.disagreement(sensorMgdl, meterMgdl) * 100) +
                "اغسل إيدك بمية ونشفها وقيس تاني قبل ما تعاير. لو الفرق لسه كبير، الحساس ممكن يكون بايظ.")
        },
        confirmButton = { TextButton(onClick = onSave) { Text("سجّلها برضه") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("هقيس تاني") } },
    )
}

/** P2-1: list of finger-sticks with delete, current slope/offset and "undo calibration". */
@Composable
fun CalibrationSection(refresh: Long) {
    val u = Repo.settings.value.unit
    val now = System.currentTimeMillis()
    val cal = remember(refresh) { Repo.calibration(now) }
    val sticks = remember(refresh) { Repo.db.sticks(now - 7 * DAY_MS).reversed() }
    var confirmUndo by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.US) }
    SectionCard("🩸 المعايرة بوخز الإصبع") {
        if (cal == Calibration.IDENTITY) Text("مفيش معايرة دلوقتي — بنعرض قراءة الحساس زي ما هي.")
        else Text("المعادلة الحالية: القراءة × %.2f %s %.0f".format(cal.slope, if (cal.offset >= 0) "+" else "−", Math.abs(cal.offset)))
        Text("آخر 7 أيام: ${sticks.size} وخزة", fontWeight = FontWeight.Bold)
        sticks.forEach { st ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${fmt.format(Date(st.timeMs))}  •  ${u.formatWithUnit(st.mgdl)}", Modifier.weight(1f))
                TextButton(onClick = { Repo.deleteStick(st.timeMs) }) { Text("🗑️ امسح") }
            }
        }
        if (sticks.isNotEmpty()) OutlinedButton(onClick = { confirmUndo = true }) { Text("↩️ إلغاء المعايرة") }
    }
    if (confirmUndo) AlertDialog(
        onDismissRequest = { confirmUndo = false },
        title = { Text("↩️ إلغاء المعايرة؟") },
        text = { Text("هنمسح وخزات آخر 7 أيام ونرجع لقراءة الحساس الأصلية.") },
        confirmButton = { TextButton(onClick = { Repo.undoCalibration(); confirmUndo = false }) { Text("أيوه، الغيها") } },
        dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("لا") } },
    )
}

/** P2-6 (ADMIN follower side): send a proposal; it is validated here AND again on the patient's phone. */
@Composable
fun ProposeThresholdsSection() {
    val s = Repo.settings.value
    val u = s.unit
    var low by remember { mutableStateOf("") }
    var high by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SectionCard("🎚️ اقترح حدود تنبيه للمريض") {
        Text("المريض لازم يوافق على موبايله قبل ما أي حاجة تتغير.")
        Row {
            OutlinedTextField(low, { low = it }, label = { Text("الانخفاض (${u.label})") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(high, { high = it }, label = { Text("الارتفاع (${u.label})") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(onClick = {
            val l = low.replace(',', '.').toDoubleOrNull()?.let(u::toMgdl)
            val h = high.replace(',', '.').toDoubleOrNull()?.let(u::toMgdl)
            val p = if (l != null && h != null) ThresholdProposal(l, h, System.currentTimeMillis()) else null
            val problem = if (p == null) "اكتب الرقمين" else p.problem(s.alerts.urgentLowMgdl)
            if (problem != null) msg = "⚠️ $problem"
            else scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { CloudSync.proposeThresholds(s, p!!.lowMgdl, p.highMgdl) }.getOrDefault(false) }
                msg = if (ok) "📤 اتبعت — مستني موافقة المريض." else "📡 مفيش اتصال، جرّب تاني."
            }
        }) { Text("📤 ابعت الاقتراح") }
        msg?.let { Text(it) }
    }
}

/** P2-4: every day drawn over a 24 h axis (12 AM on the right, like the AGP). */
@Composable
fun DailyOverlayChart(days: List<Pair<java.time.LocalDate, List<Pair<Int, Double>>>>, low: Double, high: Double) {
    val palette = listOf(0xFF1E88E5, 0xFF43A047, 0xFFE53935, 0xFF8E24AA, 0xFFFB8C00, 0xFF00ACC1, 0xFF6D4C41)
    Canvas(Modifier.fillMaxWidth().height(200.dp).padding(8.dp)) {
        fun x(m: Int) = size.width * (1 - m / 1439f)
        fun y(v: Double) = (size.height * (1 - (v.coerceIn(40.0, 300.0) - 40) / 260)).toFloat()
        drawRect(Color(0x2266BB6A), Offset(0f, y(high)), Size(size.width, y(low) - y(high)))
        days.forEachIndexed { i, (_, pts) ->
            if (pts.size < 2) return@forEachIndexed
            val p = Path()
            pts.forEachIndexed { j, (m, v) -> if (j == 0) p.moveTo(x(m), y(v)) else p.lineTo(x(m), y(v)) }
            drawPath(p, Color(palette[i % palette.size]).copy(alpha = 0.7f), style = Stroke(2f))
        }
    }
    Text("كل يوم بلون • من 12ص (يمين) لـ 11م (شمال)", fontSize = 12.sp)
}
