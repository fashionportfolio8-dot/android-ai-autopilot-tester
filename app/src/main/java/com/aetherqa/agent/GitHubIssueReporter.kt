package com.aetherqa.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GitHubIssueReporter(
    private val owner: String,
    private val repo: String,
    private val token: String
) {
    private val client = OkHttpClient()

    suspend fun createIssue(title: String, bodyMarkdown: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.github.com/repos/$owner/$repo/issues"
                val payload = JSONObject()
                    .put("title", "[AI AutoPilot] $title")
                    .put("body", bodyMarkdown)
                    .put("labels", JSONArray().put("ai-qa").put("ui-ux"))

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val respText = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val issueUrl = JSONObject(respText).optString("html_url", "Created")
                        Result.success(issueUrl)
                    } else {
                        Result.failure(Exception("GitHub API ${resp.code}: $respText"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
