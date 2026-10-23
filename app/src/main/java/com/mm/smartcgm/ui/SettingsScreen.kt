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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.MaterialTheme
import com.mm.smartcgm.cloud.CloudSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mm.smartcgm.core.AlertRule
import com.mm.smartcgm.core.DAY_MS
import com.mm.smartcgm.core.FollowerRole
import com.mm.smartcgm.core.GlucoseUnit
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

private fun fmtNum(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(java.util.Locale.US, v) else "%.1f".format(java.util.Locale.US, v)

/**
 * Commits only when the user presses Done or leaves the field, never on every keystroke (AGENTS.md §4):
 * typing "70" no longer sets the low alarm to 7 for a moment. Zero/negative/garbage input is ignored.
 */
@Composable
private fun NumField(label: String, value: Double, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    var t by remember(value) { mutableStateOf(fmtNum(value)) }
    var focused by remember { mutableStateOf(false) }
    fun commit() { t.replace(',', '.').replace('٫', '.').toDoubleOrNull()?.takeIf { it > 0 && it != value }?.let(onChange) }
    OutlinedTextField(t, { t = it },
        label = { Text(label) }, singleLine = true,
        modifier = modifier.padding(2.dp).onFocusChanged { f -> if (focused && !f.isFocused) commit(); focused = f.isFocused },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }))
}

/** A glucose threshold shown/edited in the chosen unit but always stored in mg/dL. */
@Composable
private fun GlucoseField(label: String, mgdl: Double, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    val u = Repo.settings.collectAsState().value.unit
    NumField("$label (${u.label})", u.fromMgdl(mgdl).let { if (u == GlucoseUnit.MGDL) Math.round(it).toDouble() else Math.round(it * 10) / 10.0 },
        modifier) { onChange(u.toMgdl(it)) }
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
    GlucoseField("الحد", rule.thresholdMgdl) { onChange(rule.copy(thresholdMgdl = it)) }
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
                if (s.pollJuggluco) {
                    var sec by remember { mutableStateOf(s.jugglucoSecret) }
                    OutlinedTextField(sec, { sec = it; upd { c -> c.copy(jugglucoSecret = it.trim()) } },
                        label = { Text("كلمة سر سيرفر Juggluco (لو حاطط واحدة)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = { upd { it.copy(setupDone = false) } }) { Text("🧭 افتح خطوات الإعداد تاني") }
            }

            SectionCard("🩹 الحساس") {
                Text(if (s.sensorStartMs > 0) "اتركب من %.1f يوم • %s".format((System.currentTimeMillis() - s.sensorStartMs) / DAY_MS.toDouble(), s.sensorId)
                     else "مش متسجل")
                Text("التطبيق بيعرض القراءات لحد يوم 20، ومن يوم 15 هيطلع تنبيه يومي بضرورة التغيير.")
                Text("⚠️ حساس LinX نفسه بيبطّل يبعت لوحده عند 15 يوم بالظبط، فأيام 16–20 غالباً مش هيبقى فيها قراءات. " +
                    "تطبيق LinX الرسمي بيوقف العرض بدري (حوالي يوم 10) — استخدم Juggluco عشان القراءات تكمّل لحد يوم 15. " +
                    "ما تستخدمش زرار Clear في Juggluco: القراءات بعده بتطلع غلط.", fontSize = 12.sp)
                Button(onClick = { upd { it.copy(sensorStartMs = System.currentTimeMillis(), sensorId = "", clearSuspectMs = 0) } }) { Text("🆕 ركبت حساس جديد دلوقتي") }
                // Explicit confirm button: the start time is only changed when the user presses it (AGENTS.md §4).
                var days by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(days, { days = it }, label = { Text("أو: اتركب من كام يوم؟") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    val d = days.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..30.0 }
                    Button(enabled = d != null, onClick = {
                        upd { it.copy(sensorStartMs = System.currentTimeMillis() - (d!! * DAY_MS).toLong()) }; days = ""
                    }) { Text("✔️ تأكيد") }
                }
            }
        }

        if (s.mode == AppMode.PATIENT) CalibrationSection(rememberRefreshKey())
        if (s.mode == AppMode.FOLLOWER && s.followRole == FollowerRole.ADMIN) ProposeThresholdsSection()

        SectionCard("📏 وحدة القياس") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlucoseUnit.entries.forEach { u -> FilterChip(s.unit == u, { upd { it.copy(unit = u) } }, label = { Text(u.label) }) }
            }
            Text("بتتغير في كل الشاشات والتنبيهات والملفات. الحسابات جوه التطبيق دايماً بـ mg/dL.")
        }

        SectionCard("🔻 تنبيه الانخفاض") {
            AlertRuleEditor("تشغيل تنبيه الانخفاض", s.alerts.low) { r -> upd { it.copy(alerts = it.alerts.copy(low = r)) } }
            SwitchRow("⏱️ تنبيه مبكر قبل الهبوط بـ15–20 دقيقة", s.alerts.predictiveLow) { v -> upd { it.copy(alerts = it.alerts.copy(predictiveLow = v)) } }
        }
        SectionCard("🔺 تنبيه الارتفاع") {
            AlertRuleEditor("تشغيل تنبيه الارتفاع", s.alerts.high) { r -> upd { it.copy(alerts = it.alerts.copy(high = r)) } }
        }
        SectionCard("🚨 الهبوط الخطير") {
            GlucoseField("أقل من — دايماً شغال", s.alerts.urgentLowMgdl) { v -> upd { it.copy(alerts = it.alerts.copy(urgentLowMgdl = v)) } }
            SwitchRow("🔊 تجاوز الصامت وعدم الإزعاج للانخفاض", s.alerts.criticalOverride) { v -> upd { it.copy(alerts = it.alerts.copy(criticalOverride = v)) } }
            OutlinedButton(onClick = { open(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }) { Text("🔓 اسمح بتجاوز عدم الإزعاج") }
            if (android.os.Build.VERSION.SDK_INT >= 34) OutlinedButton(onClick = {
                open(ctx, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName)))
            }) { Text("📱 اسمح للإنذار يفتح الشاشة كلها") }
        }

        if (s.mode == AppMode.PATIENT) SectionCard("💉 المعاملات الطبية (من الدكتور)") {
            val t = s.therapy
            Row { NumField("ICR جم/وحدة", t.icr, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(icr = v)) } }
                  NumField("ISF mg/وحدة", t.isf, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(isf = v)) } } }
            Row { GlucoseField("الهدف", t.targetMgdl, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(targetMgdl = v)) } }
                  NumField("مدة عمل الأنسولين (س)", t.diaHours, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(diaHours = v.coerceIn(2.0, 8.0))) } } }
            Row { NumField("أقصى جرعة", t.maxBolusUnits, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(maxBolusUnits = v)) } }
                  NumField("امتصاص الكارب (س)", t.carbAbsorptionHours, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(carbAbsorptionHours = v)) } } }
            Row { GlucoseField("النطاق من", t.lowMgdl, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(lowMgdl = v)) } }
                  GlucoseField("النطاق لحد", t.highMgdl, Modifier.weight(1f)) { v -> upd { it.copy(therapy = it.therapy.copy(highMgdl = v)) } } }
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
    val scope = rememberCoroutineScope()
    SectionCard("👨‍👩‍👧 مشاركة الأهل والمتابعة") {
        Text("المشاركة شغالة على سيرفر Cloudflare مجاني. كل موبايل ليه مفتاح سري خاص بيه.", fontSize = 12.sp)
        if (s.mode == AppMode.PATIENT) {
            var role by remember { mutableStateOf(FollowerRole.ADMIN) }
            var invite by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FollowerRole.entries.forEach { r -> FilterChip(role == r, { role = r; invite = null }, label = { Text(r.arabic) }) }
            }
            Text(if (role.receivesCritical) "🚨 بيستقبل إنذارات الطوارئ ويقدر يسجل جرعات وأكل." else "👀 بيشوف بس من غير إنذارات ولا تعديل.")
            Button(enabled = !busy, onClick = {
                busy = true; failed = false
                scope.launch {
                    val c = withContext(Dispatchers.IO) { runCatching { CloudSync.createInvite(s, role) }.getOrNull() }
                    invite = c; failed = c == null; busy = false
                }
            }) { Text(if (busy) "⏳ ..." else "🎟️ اعمل كود متابعة") }
            if (failed) Text("مفيش نت أو السيرفر مش متاح — جرّب تاني.", color = MaterialTheme.colorScheme.error)
            invite?.let { code ->
                val bmp = remember(code) { qr("SMARTCGM:$code") }
                Image(bmp.asImageBitmap(), null, Modifier.size(200.dp))
                Text("الكود: $code  (يستخدم مرة واحدة، صالح 24 ساعة)")
                OutlinedButton(onClick = {
                    open(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "كود متابعة السكر (صالح 24 ساعة): $code"), "ابعت الكود"))
                }) { Text("📤 ابعت الكود") }
            }
            Text("— أو —")
            var code by remember { mutableStateOf("") }
            var err by remember { mutableStateOf(false) }
            OutlinedTextField(code, { code = it; err = false }, label = { Text("انضم كمتابع: اكتب كود المريض") }, singleLine = true, isError = err)
            Button(enabled = !busy, onClick = {
                val c = Pairing.parseInvite(code)
                if (c == null) err = true
                else { busy = true; scope.launch {
                    // Keeps this phone's OWN patient code and token; the follower token is stored separately (P1-5).
                    err = !withContext(Dispatchers.IO) { runCatching { CloudSync.redeemInvite(s, c) }.getOrDefault(false) }
                    busy = false
                } }
            }) { Text("🔗 انضم كمتابع") }
            if (err) Text("الكود غلط أو انتهى أو مستخدم قبل كده.", color = MaterialTheme.colorScheme.error)
        } else {
            Text("أنت متابع (${s.followRole.arabic}) لحساب ${s.followAccountCode.take(4)}••••")
            OutlinedButton(onClick = {
                // Going back must NOT create a new code: the patient's existing followers stay linked.
                Repo.updateSettings { it.copy(mode = AppMode.PATIENT) }
            }) { Text("↩️ ارجع لوضع المريض") }
        }
        var url by remember { mutableStateOf(s.backendUrl) }
        OutlinedTextField(url, { url = it; Repo.updateSettings { c -> c.copy(backendUrl = it.trim()) } },
            label = { Text("عنوان السيرفر (متقدم)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}
