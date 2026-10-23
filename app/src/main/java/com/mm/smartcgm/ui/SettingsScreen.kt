package com.mm.smartcgm.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mm.smartcgm.core.AlertRule
import com.mm.smartcgm.core.DAY_MS
import com.mm.smartcgm.core.FollowerRole
import com.mm.smartcgm.core.Pairing
import com.mm.smartcgm.data.AppMode
import com.mm.smartcgm.data.Repo

private fun open(ctx: Context, i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

private fun qr(text: String, px: Int = 400): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px)
    return Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565).apply {
        for (x in 0 until px) for (y in 0 until px) setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }
}

@Composable
private fun NumField(label: String, value: Double, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    var t by remember { mutableStateOf(if (value % 1.0 == 0.0) "%.0f".format(value) else value.toString()) }
    OutlinedTextField(t, { t = it; it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v > 0 }?.let(onChange) },
        label = { Text(label) }, singleLine = true, modifier = modifier.padding(2.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f)); Switch(on, onChange)
    }
}

@Composable
private fun AlertRuleEditor(title: String, rule: AlertRule, onChange: (AlertRule) -> Unit) {
    SwitchRow(title, rule.enabled) { onChange(rule.copy(enabled = it)) }
    if (!rule.enabled) return
    NumField("الحد (mg/dL)", rule.thresholdMgdl) { onChange(rule.copy(thresholdMgdl = it)) }
    Text("🔁 كرر التنبيه كل:")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(rule.repeatMinutes == 5, { onChange(rule.copy(repeatMinutes = 5)) }, label = { Text("5 د") })
        FilterChip(rule.repeatMinutes == 15, { onChange(rule.copy(repeatMinutes = 15)) }, label = { Text("15 د") })
        NumField("مخصص (د)", rule.repeatMinutes.toDouble(), Modifier.weight(1f)) { onChange(rule.copy(repeatMinutes = it.toInt().coerceIn(1, 240))) }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val s by Repo.settings.collectAsState()
    fun upd(f: (com.mm.smartcgm.data.AppSettings) -> com.mm.smartcgm.data.AppSettings) = Repo.updateSettings(f)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (s.mode == AppMode.PATIENT) {
            SectionCard("📡 مصدر البيانات (الحساس)") {
                Text("1️⃣ نزّل تطبيق Juggluco وفعّل فيه الحساس (LinX بتصوير الكود اللي على العلبة، ليبري بالـNFC، سيبيونكس بالكود).")
                Text("2️⃣ في Juggluco: الإعدادات ← Broadcast ← فعّل glucodata واختار تطبيقنا، وفعّل Web server.")
                Text("➕ أو سيب تطبيق LinX الرسمي شغال واسمح لينا نقرا إشعاره.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=tk.glucodata"))) }) { Text("⬇️ Juggluco") }
                    OutlinedButton(onClick = { open(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("🔔 قراءة الإشعارات") }
                }
                OutlinedButton(onClick = {
                    open(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                }) { Text("🔋 اشتغل في الخلفية من غير توقف") }
                SwitchRow("سحب القراءات من سيرفر Juggluco المحلي", s.pollJuggluco) { v -> upd { it.copy(pollJuggluco = v) } }
            }

            SectionCard("🩹 الحساس") {
                Text(if (s.sensorStartMs > 0) "اتركب من %.1f يوم • %s".format((System.currentTimeMillis() - s.sensorStartMs) / DAY_MS.toDouble(), s.sensorId)
                     else "مش متسجل")
                Text("بيشتغل لحد 20 يوم، ومن يوم 15 هيطلع تنبيه يومي بضرورة التغيير.")
                Button(onClick = { upd { it.copy(sensorStartMs = System.currentTimeMillis(), sensorId = "") } }) { Text("🆕 ركبت حساس جديد دلوقتي") }
                NumField("أو: اتركب من كام يوم؟", 0.0) { d ->
                    upd { it.copy(sensorStartMs = System.currentTimeMillis() - (d * DAY_MS).toLong()) }
                }
            }
        }

        SectionCard("🔻 تنبيه الانخفاض") {
            AlertRuleEditor("تشغيل تنبيه الانخفاض", s.alerts.low) { r -> upd { it.copy(alerts = it.alerts.copy(low = r)) } }
            SwitchRow("⏱️ تنبيه مبكر قبل الهبوط بـ15–20 دقيقة", s.alerts.predictiveLow) { v -> upd { it.copy(alerts = it.alerts.copy(predictiveLow = v)) } }
        }
        SectionCard("🔺 تنبيه الارتفاع") {
            AlertRuleEditor("تشغيل تنبيه الارتفاع", s.alerts.high) { r -> upd { it.copy(alerts = it.alerts.copy(high = r)) } }
        }
        SectionCard("🚨 الهبوط الخطير") {
            NumField("أقل من (mg/dL) — دايماً شغال", s.alerts.urgentLowMgdl) { v -> upd { it.copy(alerts = it.alerts.copy(urgentLowMgdl = v)) } }
            SwitchRow("🔊 تجاوز الصامت وعدم الإزعاج للانخفاض", s.alerts.criticalOverride) { v -> upd { it.copy(alerts = it.alerts.copy(criticalOverride = v)) } }
            OutlinedButton(onClick = { open(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }) { Text("🔓 اسمح بتجاوز عدم الإزعاج") }
        }

        if (s.mode == AppMode.PATIENT) SectionCard("💉 المعاملات الطبية (من الدكتور)") {
            val t = s.therapy
            Row { NumField("ICR جم/وحدة", t.icr, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(icr = v)) } }
                  NumField("ISF mg/وحدة", t.isf, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(isf = v)) } } }
            Row { NumField("الهدف", t.targetMgdl, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(targetMgdl = v)) } }
                  NumField("مدة عمل الأنسولين (س)", t.diaHours, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(diaHours = v.coerceIn(2.0, 8.0))) } } }
            Row { NumField("أقصى جرعة", t.maxBolusUnits, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(maxBolusUnits = v)) } }
                  NumField("امتصاص الكارب (س)", t.carbAbsorptionHours, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(carbAbsorptionHours = v)) } } }
            Row { NumField("النطاق من", t.lowMgdl, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(lowMgdl = v)) } }
                  NumField("النطاق لحد", t.highMgdl, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(highMgdl = v)) } } }
        }

        if (s.mode == AppMode.PATIENT) SectionCard("🤖 الذكاء الاصطناعي (الكاميرا)") {
            var key by remember { mutableStateOf(s.geminiKey) }
            OutlinedTextField(key, { key = it; upd { c -> c.copy(geminiKey = it.trim()) } }, label = { Text("مفتاح Gemini API") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            var model by remember { mutableStateOf(s.geminiModel) }
            OutlinedTextField(model, { model = it; upd { c -> c.copy(geminiModel = it.trim()) } }, label = { Text("الموديل") }, singleLine = true)
            Text("المفتاح مجاني من aistudio.google.com — بيتخزن على الموبايل بس.")
        }

        FamilySection(ctx)
        Text("⚕️ التطبيق مساعد مش بديل للدكتور. أي جرعة مقترحة لازم تتأكد منها.", Modifier.padding(16.dp))
    }
}

@Composable
private fun FamilySection(ctx: Context) {
    val s by Repo.settings.collectAsState()
    SectionCard("👨‍👩‍👧 مشاركة الأهل والمتابعة") {
        var url by remember { mutableStateOf(s.firebaseUrl) }
        OutlinedTextField(url, { url = it; Repo.updateSettings { c -> c.copy(firebaseUrl = it.trim()) } },
            label = { Text("رابط قاعدة Firebase") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (s.mode == AppMode.PATIENT) {
            if (s.firebaseUrl.isBlank()) { Text("حط الرابط الأول عشان تقدر تضيف متابعين."); return@SectionCard }
            var role by remember { mutableStateOf(FollowerRole.ADMIN) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FollowerRole.entries.forEach { r -> FilterChip(role == r, { role = r }, label = { Text(r.arabic) }) }
            }
            Text(if (role.receivesCritical) "🚨 بيستقبل إنذارات الطوارئ ويقدر يسجل جرعات وأكل." else "👀 بيشوف بس من غير إنذارات ولا تعديل.")
            val invite = "SMARTCGM:" + Pairing.inviteCode(s.accountCode, role)
            val bmp = remember(invite) { qr(invite) }
            Image(bmp.asImageBitmap(), null, Modifier.size(200.dp))
            Text("الكود: ${invite.removePrefix("SMARTCGM:")}")
            OutlinedButton(onClick = {
                open(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "كود متابعة السكر: ${invite.removePrefix("SMARTCGM:")}\nرابط القاعدة: ${s.firebaseUrl}"), "ابعت الكود"))
            }) { Text("📤 ابعت الكود") }
            Text("— أو —")
            var code by remember { mutableStateOf("") }
            var err by remember { mutableStateOf(false) }
            OutlinedTextField(code, { code = it; err = false }, label = { Text("انضم كمتابع: اكتب كود المريض") }, singleLine = true, isError = err)
            Button(onClick = {
                val p = Pairing.parseInvite(code)
                if (p == null) err = true
                else Repo.updateSettings { it.copy(mode = AppMode.FOLLOWER, accountCode = p.first, followRole = p.second) }
            }) { Text("🔗 انضم كمتابع") }
        } else {
            Text("أنت متابع (${s.followRole.arabic}) لحساب ${s.accountCode.take(4)}••••")
            OutlinedButton(onClick = {
                Repo.updateSettings { it.copy(mode = AppMode.PATIENT, accountCode = Pairing.newAccountCode()) }
            }) { Text("↩️ ارجع لوضع المريض") }
        }
    }
}
