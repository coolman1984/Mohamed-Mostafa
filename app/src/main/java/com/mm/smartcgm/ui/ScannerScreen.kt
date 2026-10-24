package com.mm.smartcgm.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mm.smartcgm.ai.MealEstimate
import com.mm.smartcgm.ai.MedicationScan
import com.mm.smartcgm.ai.VisionAi
import com.mm.smartcgm.core.GlucoseEffect
import com.mm.smartcgm.core.MedicationEffects
import com.mm.smartcgm.core.MedicationEntry
import com.mm.smartcgm.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Downscale to ~1280 px: enough for the AI, small enough to upload fast. */
private fun loadScaled(ctx: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}.getOrNull()

@Composable
fun ScannerScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mealMode by remember { mutableStateOf(true) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var meal by remember { mutableStateOf<MealEstimate?>(null) }
    var med by remember { mutableStateOf<MedicationScan?>(null) }
    var showBolus by remember { mutableStateOf(false) }
    var savedMed by remember { mutableStateOf(false) }

    val photoFile = remember { File(ctx.cacheDir, "photos").apply { mkdirs() }.let { File(it, "shot.jpg") } }
    val photoUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", photoFile) }

    fun analyze(bmp: Bitmap) {
        photo = bmp; meal = null; med = null; error = null; savedMed = false
        val s = Repo.settings.value
        if (s.geminiKey.isBlank()) { error = "🔑 حط مفتاح Gemini في الإعدادات الأول."; return }
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (mealMode) meal = VisionAi.estimateMeal(s.geminiKey, s.geminiModel, bmp)
                    else med = VisionAi.scanMedication(s.geminiKey, s.geminiModel, bmp)
                }
            }.onFailure { error = "❌ ${it.message}" }
            busy = false
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) loadScaled(ctx, photoUri)?.let(::analyze)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadScaled(ctx, it) }?.let(::analyze)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mealMode, { mealMode = true }, label = { Text("🍽️ وجبة") })
            FilterChip(!mealMode, { mealMode = false }, label = { Text("💊 دوا") })
        }
        Text(if (mealMode) "صوّر الطبق من فوق وفي نور كويس." else "صوّر اسم الدوا على العلبة أو الشريط بوضوح.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { camera.launch(photoUri) }, Modifier.weight(1f)) { Text("📸 صوّر") }
            OutlinedButton(onClick = { gallery.launch("image/*") }, Modifier.weight(1f)) { Text("🖼️ من المعرض") }
        }
        photo?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(220.dp).padding(vertical = 8.dp)) }
        if (busy) Row { CircularProgressIndicator(); Text("  🤖 بيحلل الصورة…") }
        error?.let { Text(it, color = Red) }

        meal?.let { m ->
            SectionCard("🍽️ تقدير الوجبة") {
                m.items.forEach { Text("• $it") }
                Text("🍞 كربوهيدرات: %.0f جم".format(m.carbsGrams), fontWeight = FontWeight.Bold)
                Text("🔥 سعرات: %.0f".format(m.calories))
                Text("🎯 الثقة: ${m.confidence}")
                if (m.notes.isNotBlank()) Text("📝 ${m.notes}")
                Button(onClick = { showBolus = true }, Modifier.padding(top = 8.dp)) { Text("🧮 احسب الجرعة لهذه الوجبة") }
            }
        }

        med?.let { m ->
            val local = MedicationEffects.lookup("${m.name} ${m.activeIngredient}")
            val effect = local?.effect ?: when (m.glucoseEffect) {
                "raises" -> GlucoseEffect.RAISES; "lowers" -> GlucoseEffect.LOWERS; else -> GlucoseEffect.NONE
            }
            SectionCard("💊 الدوا") {
                Text("الاسم: ${m.name}", fontWeight = FontWeight.Bold)
                Text("المادة الفعالة: ${m.activeIngredient}")
                Text(when (effect) { GlucoseEffect.RAISES -> "📈 ممكن يرفع السكر"; GlucoseEffect.LOWERS -> "📉 بيخفض السكر"; else -> "➖ تأثير بسيط أو مفيش" })
                (local?.warning ?: m.warning).takeIf { it.isNotBlank() }?.let { Text("⚠️ $it", color = Orange) }
                if (savedMed) Text("✅ اتسجل في السجل الطبي")
                else Button(onClick = {
                    Repo.db.addMed(MedicationEntry(System.currentTimeMillis(), m.name, m.activeIngredient, effect))
                    Repo.touch(); savedMed = true
                }, Modifier.padding(top = 8.dp)) { Text("📝 سجّل الدوا بالوقت والتاريخ") }
            }
        }
    }

    if (showBolus) {
        BolusDialog(initialCarbs = meal?.carbsGrams ?: 0.0) { showBolus = false }
    }
}
