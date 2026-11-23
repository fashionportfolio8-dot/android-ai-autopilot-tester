package com.aetherqa.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class AgentDecision(
    val action: String,
    val x: Float = 500f,
    val y: Float = 1000f,
    val endX: Float = 500f,
    val endY: Float = 500f,
    val bugFound: Boolean = false,
    val bugTitle: String = "",
    val uxNotes: String = ""
)

class GeminiVisionClient(
    private val apiKey: String,
    private val model: String = "gemini-2.5-flash"
) {
    private val client = OkHttpClient()

    suspend fun evaluateScreenAndDecide(
        base64Png: String,
        uiSummary: String,
        goalPrompt: String
    ): AgentDecision = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val instruction = """
            You are an autonomous Android App & Game QA + UI/UX Tester Agent.
            Goal: $goalPrompt
            Visible UI elements:
            $uiSummary

            Inspect the screenshot for:
            1. UI/UX issues (low contrast, overlapping text, tiny tap targets, clipped elements, bad spacing).
            2. Functional/game bugs (frozen screen, error dialogs, broken layouts, dead-end states).
            3. Next auto-pilot action to explore the app/game.

            Respond ONLY with valid JSON in this exact format:
            {
              "action": "TAP",
              "x": 540,
              "y": 1200,
              "endX": 540,
              "endY": 600,
              "bugFound": false,
              "bugTitle": "",
              "uxNotes": "Short UI/UX and QA observation"
            }
        """.trimIndent()

        val parts = JSONArray()
            .put(JSONObject().put("text", instruction))
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/png")
                        .put("data", base64Png)
                )
            )

        val bodyJson = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext AgentDecision(
                    action = "WAIT",
                    uxNotes = "Gemini API error ${response.code}: $raw"
                )
            }
            parseDecision(raw)
        }
    }

    private fun parseDecision(apiResponseText: String): AgentDecision {
        return try {
            val root = JSONObject(apiResponseText)
            val candidates = root.optJSONArray("candidates") ?: return AgentDecision("WAIT", uxNotes = "No candidates")
            val textPart = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .optString("text", "")

            val cleanJson = textPart
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val obj = JSONObject(cleanJson)
            AgentDecision(
                action = obj.optString("action", "WAIT").uppercase(),
                x = obj.optDouble("x", 540.0).toFloat(),
                y = obj.optDouble("y", 1200.0).toFloat(),
                endX = obj.optDouble("endX", 540.0).toFloat(),
                endY = obj.optDouble("endY", 600.0).toFloat(),
                bugFound = obj.optBoolean("bugFound", false),
                bugTitle = obj.optString("bugTitle", "UI/UX or QA Issue Detected"),
                uxNotes = obj.optString("uxNotes", "")
            )
        } catch (e: Exception) {
            AgentDecision(action = "WAIT", uxNotes = "Parse fallback: ${e.message}")
        }
    }
}
