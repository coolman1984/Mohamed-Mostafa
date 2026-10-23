package com.mm.smartcgm.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mm.smartcgm.data.Repo

private fun go(ctx: Context, i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

private fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

/** AGENTS.md P0-4: first-launch, step-by-step Arabic setup for a non-technical person. */
@Composable
fun SetupWizard() {
    val ctx = LocalContext.current
    val hasJuggluco = installed(ctx, "tk.glucodata")
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🧭 خطوات التشغيل (مرة واحدة بس)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text((if (hasJuggluco) "✅" else "1️⃣") + " نزّل تطبيق Juggluco المجاني (هو اللي بيكلم الحساس).")
            if (!hasJuggluco) OutlinedButton(onClick = {
                go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=tk.glucodata")))
            }) { Text("⬇️ نزّل Juggluco") }
            Text("2️⃣ افتح Juggluco وشغّل الحساس: LinX بتصوير الكود اللي على العلبة، ليبري بلمس الموبايل للحساس، سيبيونكس بالكود.")
            if (hasJuggluco) OutlinedButton(onClick = {
                ctx.packageManager.getLaunchIntentForPackage("tk.glucodata")?.let { go(ctx, it) }
            }) { Text("📲 افتح Juggluco") }
            Text("3️⃣ في Juggluco: Settings ← Broadcast ← فعّل glucodata واختار «متابع السكر الذكي». وفعّل كمان Web server.")
            Text("4️⃣ اسمح للتطبيق بالحاجات دي:")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { go(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("🔔 الإشعارات") }
                OutlinedButton(onClick = {
                    go(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                }) { Text("🔋 البطارية") }
                OutlinedButton(onClick = { go(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }) { Text("🔕 عدم الإزعاج") }
            }
            Button(onClick = { Repo.updateSettings { it.copy(setupDone = true) } }) { Text("👍 خلصت") }
        }
    }
}

private val SOURCE_NAMES = linkedMapOf(
    "juggluco" to "Juggluco (بث)",
    "juggluco-web" to "Juggluco (سيرفر)",
    "xdrip-broadcast" to "بث xDrip",
    "notification" to "إشعار تطبيق الحساس",
)

/** AGENTS.md P0-4: which source is alive, and a red banner when nothing arrived for 10 minutes. */
@Composable
fun SourceHealthCard(lastReadingMs: Long?, now: Long) {
    val s by Repo.settings.collectAsState()
    val stale = lastReadingMs == null || now - lastReadingMs > 10 * 60_000L
    val red = Color(0xFFC62828)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (stale) red.copy(alpha = 0.12f) else Color(0x112E7D32))) {
        Column(Modifier.padding(12.dp)) {
            if (stale) Text("⚠️ مفيش قراءات وصلت من أكتر من 10 دقايق — راجع Juggluco أو تطبيق الحساس.", color = red, fontWeight = FontWeight.Bold)
            else Text("✅ البيانات بتوصل كويس", fontWeight = FontWeight.Bold)
            SOURCE_NAMES.forEach { (key, name) ->
                val t = Repo.sourceSeen[key]
                if (t != null) Text("• $name: من ${((now - t) / 60_000).coerceAtLeast(0)} دقيقة")
            }
            if (s.pollJuggluco) when (Repo.lastJugglucoWebStatus) {
                0 -> Text("• سيرفر Juggluco مش شغال (فعّل Web server جوه Juggluco).")
                403 -> Text("• سيرفر Juggluco رافض: كلمة السر غلط.", color = red)
                else -> Unit
            }
        }
    }
}
