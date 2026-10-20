package org.blindpilot.android.local

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.blindpilot.android.core.Cancellation
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CatalogModel(val id: String, val downloads: Long, val likes: Long, val gated: Boolean)
data class CatalogFile(val name: String, val url: String, val bytes: Long, val sha256: String)

/** Browses Hugging Face's public GGUF index. Blocking; call from a worker thread. */
class ModelCatalog(
    private val base: String = "https://huggingface.co",
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
) {
    fun search(query: String, sort: String, cancellation: Cancellation): List<CatalogModel> {
        require(sort in SORTS.values) { "Unknown sort order." }
        val url = "$base/api/models".toHttpUrl().newBuilder().addQueryParameter("library", "gguf")
            .addQueryParameter("sort", sort).addQueryParameter("limit", "100").apply { if (query.isNotBlank()) addQueryParameter("search", query.trim()) }.build()
        val rows = get(url.toString(), cancellation)
        return (0 until rows.length()).map { rows.getJSONObject(it) }.map {
            // "gated" is false, or a string such as "auto" or "manual" when access must be requested.
            CatalogModel(it.getString("id"), it.optLong("downloads"), it.optLong("likes"), it.opt("gated").let { g -> g != null && g != false && g.toString() != "false" && g.toString() != "null" })
        }
    }

    /** Single-file GGUF models only: split shards and vision projectors cannot be loaded by this runtime. */
    fun files(modelId: String, cancellation: Cancellation): List<CatalogFile> {
        require(modelId.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Invalid model id." }
        val rows = get("$base/api/models/$modelId/tree/main?recursive=true", cancellation)
        return (0 until rows.length()).map { rows.getJSONObject(it) }.filter { row ->
            val path = row.optString("path")
            row.optString("type") == "file" && path.endsWith(".gguf", true) && !path.contains("mmproj", true) &&
                !Regex("-\\d{5}-of-\\d{5}\\.gguf$").containsMatchIn(path) && row.optJSONObject("lfs")?.optString("oid")?.length == 64
        }.map { row ->
            val path = row.getString("path")
            CatalogFile(path.substringAfterLast('/'), "$base/$modelId/resolve/main/" + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") },
                row.optLong("size"), row.getJSONObject("lfs").getString("oid"))
        }.sortedBy { it.bytes }
    }

    private fun get(url: String, cancellation: Cancellation): JSONArray {
        cancellation.check()
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        cancellation.onCancel(call::cancel)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Hugging Face returned HTTP ${response.code}.")
            return JSONArray(response.body?.string().orEmpty())
        }
    }

    companion object {
        val SORTS = linkedMapOf("Most downloaded" to "downloads", "Most liked" to "likes", "Trending" to "trendingScore", "Recently updated" to "lastModified")
    }
}
