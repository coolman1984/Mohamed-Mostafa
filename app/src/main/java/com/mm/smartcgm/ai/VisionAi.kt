package com.mm.smartcgm.ai

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class MealEstimate(
    val items: List<String>,
    val carbsGrams: Double,
    val calories: Double,
    val confidence: String,
    val notes: String,
)

data class MedicationScan(
    val name: String,
    val activeIngredient: String,
    val glucoseEffect: String, // raises | lowers | none
    val warning: String,
)

/** Photo → Gemini (Vision) → structured JSON. The user supplies their own API key. */
object VisionAi {

    private const val MEAL_PROMPT = """
You are a diabetes dietitian. Look at the food photo and estimate the portion actually on the plate.
Reply ONLY with JSON:
{"items":["name (grams)",...],"carbs_g":number,"calories":number,"confidence":"low|medium|high","notes":"short Arabic note"}
Count only digestible carbohydrates (exclude fiber). Item names in Arabic (Egyptian dishes are common)."""

    private const val MED_PROMPT = """
Read the medicine box / strip in the photo (OCR). Reply ONLY with JSON:
{"name":"brand name","active_ingredient":"generic name and strength","glucose_effect":"raises|lowers|none",
 "warning":"one short Arabic sentence on how it may affect blood sugar for a diabetic"}
Corticosteroids and sugary syrups raise glucose; insulin and diabetes drugs lower it."""

    private fun call(key: String, model: String, prompt: String, photo: Bitmap): JSONObject {
        val jpeg = ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", prompt.trim()))
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))))))
            .put("generationConfig", JSONObject().put("response_mime_type", "application/json").put("temperature", 0.2))
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IllegalStateException("خطأ من خدمة الذكاء الاصطناعي (${c.responseCode}): ${err.take(200)}")
            }
            val resp = JSONObject(c.inputStream.bufferedReader().readText())
            val text = resp.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            return JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        } finally {
            c.disconnect()
        }
    }

    fun estimateMeal(key: String, model: String, photo: Bitmap): MealEstimate {
        val o = call(key, model, MEAL_PROMPT, photo)
        val items = o.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return MealEstimate(items, o.optDouble("carbs_g", 0.0), o.optDouble("calories", 0.0),
            o.optString("confidence", "low"), o.optString("notes"))
    }

    fun scanMedication(key: String, model: String, photo: Bitmap): MedicationScan {
        val o = call(key, model, MED_PROMPT, photo)
        return MedicationScan(o.optString("name"), o.optString("active_ingredient"),
            o.optString("glucose_effect", "none"), o.optString("warning"))
    }
}
