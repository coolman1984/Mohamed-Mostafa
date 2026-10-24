package com.mm.smartcgm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mm.smartcgm.cloud.CloudSync
import com.mm.smartcgm.core.BleReadingValidator
import com.mm.smartcgm.core.BolusAdvisor
import com.mm.smartcgm.core.Calibration
import com.mm.smartcgm.core.CarbEntry
import com.mm.smartcgm.core.Fingerstick
import com.mm.smartcgm.core.FollowerRole
import com.mm.smartcgm.core.HOUR_MS
import com.mm.smartcgm.core.InsulinDose
import com.mm.smartcgm.core.Prediction
import com.mm.smartcgm.core.SensorPhase
import com.mm.smartcgm.core.Trend
import com.mm.smartcgm.data.AppMode
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.engine.Pipeline
import kotlinx.coroutines.launch

private enum class Entry { STICK, INSULIN, CARBS, BOLUS }

@Composable
fun HomeScreen() {
    val key = rememberRefreshKey()
    val s by Repo.settings.collectAsState()
    val now = System.currentTimeMillis()
    val recent = remember(key) { Repo.readings(now - 3 * HOUR_MS) }
    val last = recent.lastOrNull()
    val arrow = remember(key) { Trend.arrow(recent) }
    val forecast = remember(key) { Prediction.forecast(Repo.dosingReadings(now), s.alerts.low.thresholdMgdl) }
    val sensor = remember(key) { Repo.sensorStatus(now) }
    val iob = remember(key) { Repo.iob(now) }
    val cob = remember(key) { Repo.cob(now) }
    var entry by remember { mutableStateOf<Entry?>(null) }
    var suspectStick by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val follower = s.mode == AppMode.FOLLOWER
    val canEdit = !follower || s.followRole == FollowerRole.ADMIN

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (follower) {
            Text("👀 وضع المتابع — ${s.followRole.arabic}", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.secondary)
        }
        if (!follower && !s.setupDone) SetupWizard()
        if (!follower) SourceHealthCard(last?.timeMs, now)
        if (!follower && s.clearSuspectMs > 0) ClearSuspectCard()
        if (!follower && s.newSensorSuspectMs > 0) NewSensorPrompt()
        val proposal = remember(key) { if (!follower) Repo.pendingProposal() else null }
        proposal?.let { ProposalCard(it) }
        val experimental = remember(key) { Repo.db.experimentalReadings(now - 3 * HOUR_MS).lastOrNull() }
        experimental?.let {
            SectionCard("🧪 قراءة تجريبية منفصلة") {
                Text("${s.unit.formatWithUnit(it.mgdl)} — غير معتمدة للجرعات أو التنبيهات الطبية", color = Orange)
                Text(if (BleReadingValidator.isStale(it, now)) "⚠️ آخر قراءة قديمة، مش قراءة حية" else "للمقارنة والاختبار فقط — اتأكد بقياس الإصبع")
            }
        }
        // Big current value
        val color = last?.let { colorFor(it.mgdl, s.therapy.lowMgdl, s.therapy.highMgdl) } ?: Color.Gray
        Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (last == null) {
                    Text("📡 مستني أول قراءة من الحساس", style = MaterialTheme.typography.titleLarge)
                    Text("افتح الإعدادات ← مصدر البيانات وشغّل الربط.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.unit.format(last.mgdl), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = color)
                        Text(" ${arrow.symbol}", fontSize = 56.sp, color = color)
                    }
                    Text("${s.unit.label} • ${arrow.arabic}")
                    if (sensor?.phase == SensorPhase.EXTENDED) Text("🧪 الحساس عدّى عمره المحدد — القراءة غير معتمدة للجرعات", color = Red)
                    if (BleReadingValidator.isStale(last, now)) Text("⚠️ آخر قراءة معروفة — البيانات مش حية", color = Red)
                    val ago = (now - last.timeMs) / 60_000
                    Text(if (ago < 1) "🕐 دلوقتي" else "🕐 من $ago دقيقة", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        forecast?.minutesToLow?.takeIf { it > 0 }?.let {
            Text("⏱️ متوقع هبوط خلال حوالي $it دقيقة — خد احتياطك.", Modifier.padding(horizontal = 16.dp),
                color = Red, fontWeight = FontWeight.Bold)
        }

        sensor?.let { SensorRing(it) } ?: Text("🩹 تاريخ تركيب الحساس مش متسجل — حدده من الإعدادات.", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("💉 أنسولين فعال: %.1f وحدة".format(iob))
            Text("🍞 كارب فعال: %.0f جم".format(cob))
        }

        SectionCard("📈 آخر 3 ساعات") {
            GlucoseChart(recent, now - 3 * HOUR_MS, now, s.therapy.lowMgdl, s.therapy.highMgdl)
        }

        if (canEdit) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!follower) OutlinedButton(onClick = { entry = Entry.STICK }, Modifier.weight(1f)) { Text("🩸 وخزة") }
                OutlinedButton(onClick = { entry = Entry.INSULIN }, Modifier.weight(1f)) { Text("💉 جرعة") }
                OutlinedButton(onClick = { entry = Entry.CARBS }, Modifier.weight(1f)) { Text("🍞 أكل") }
            }
            if (!follower) Button(onClick = { entry = Entry.BOLUS }, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text("🧮 احسب الجرعة المقترحة")
            }
        }
    }

    val t = System.currentTimeMillis()
    fun saveDose(u: Double) = if (follower) Pipeline.scope.launch { CloudSync.sendEvent(s, "dose", u) }
        else { Repo.db.addDose(InsulinDose(t, u)); Repo.touch(); null }
    fun saveCarbs(g: Double) = if (follower) Pipeline.scope.launch { CloudSync.sendEvent(s, "carbs", g) }
        else { Repo.db.addCarbs(CarbEntry(t, g)); Repo.touch(); null }

    when (entry) {
        // The meter value is typed in the display unit and stored in mg/dL (otherwise 6.5 mmol/L would be saved as 6.5 mg/dL).
        Entry.STICK -> NumberDialog("🩸 قياس وخز الإصبع (للمعايرة)", s.unit.label, { entry = null }) {
            val mg = s.unit.toMgdl(it)
            if (mg in 20.0..600.0) {
                // P2-1: compare with the RAW sensor value (what calibration will pair it with).
                val raw = Repo.db.readings(t - 10 * 60_000L).lastOrNull()?.mgdl
                if (Calibration.isSuspicious(raw, mg)) suspectStick = raw!! to mg
                else Repo.addStick(Fingerstick(t, mg))
            }
        }
        Entry.INSULIN -> NumberDialog("💉 جرعة أنسولين سريع", "وحدات", { entry = null }) { saveDose(it) }
        Entry.CARBS -> NumberDialog("🍞 كربوهيدرات", "جرام", { entry = null }) { saveCarbs(it) }
        Entry.BOLUS -> BolusDialog { entry = null }
        null -> Unit
    }
    suspectStick?.let { (sensorMg, meterMg) ->
        StickWarningDialog(sensorMg, meterMg, onSave = { Repo.addStick(Fingerstick(t, meterMg)); suspectStick = null },
            onCancel = { suspectStick = null })
    }
}

@Composable
fun BolusDialog(initialCarbs: Double = 0.0, onDismiss: () -> Unit) {
    rememberRefreshKey()
    val s = Repo.settings.value
    val now = System.currentTimeMillis()
    val eligible = Repo.dosingReadings(now)
    if (eligible.isEmpty()) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("⚠️ حساب الجرعة متوقف") },
            text = { Text("لازم قراءة حديثة من مصدر موثوق وحساس داخل عمره المحدد. القراءات التجريبية أو القديمة مش مناسبة لحساب الجرعة.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } })
        return
    }
    val currentMgdl = eligible.last().mgdl
    val iob = Repo.iob(now)
    val predictedLow = Prediction.forecast(eligible, s.alerts.low.thresholdMgdl)?.minutesToLow != null
    var carbs by remember { mutableStateOf(if (initialCarbs > 0) "%.0f".format(initialCarbs) else "") }
    val g = carbs.replace(',', '.').toDoubleOrNull() ?: 0.0
    val sug = BolusAdvisor.suggest(g, currentMgdl, iob, s.therapy, predictedLow)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🧮 الجرعة المقترحة") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(carbs, { carbs = it }, label = { Text("كربوهيدرات الوجبة (جم)") }, singleLine = true)
                Text("🍞 جرعة الأكل: %.1f".format(sug.mealUnits))
                Text("📉 جرعة التصحيح: %.1f".format(sug.correctionUnits))
                Text("💉 ناقص الأنسولين الفعال: %.1f".format(sug.iobUnits))
                Text("✅ المقترح: %.1f وحدة".format(sug.totalUnits), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                sug.warnings.forEach { Text("⚠️ $it", color = Red) }
                Text("ℹ️ اقتراح حسابي بس — القرار النهائي ليك وللدكتور.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Recheck at confirmation: the dialog may have stayed open past freshness/lifecycle limits.
                val t = System.currentTimeMillis()
                val confirmed = Repo.dosingReadings(t)
                if (confirmed.isEmpty()) { onDismiss(); return@TextButton }
                val checked = BolusAdvisor.suggest(g, confirmed.last().mgdl, Repo.iob(t), Repo.settings.value.therapy,
                    Prediction.forecast(confirmed, Repo.settings.value.alerts.low.thresholdMgdl)?.minutesToLow != null)
                if (checked.totalUnits != sug.totalUnits || checked.blockedForLow != sug.blockedForLow) {
                    Repo.touch() // refresh the displayed proposal before allowing another confirmation
                    return@TextButton
                }
                if (g > 0) Repo.db.addCarbs(CarbEntry(t, g))
                if (sug.totalUnits > 0) Repo.db.addDose(InsulinDose(t, sug.totalUnits))
                Repo.touch(); onDismiss()
            }) { Text("سجّل الأكل والجرعة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}
