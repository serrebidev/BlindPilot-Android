package org.blindpilot.android.backends

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.blindpilot.android.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ProviderCatalog {
    data class Provider(val id: String, val label: String, val baseUrl: String, val apiMode: String = "chat_completions")
    val providers = listOf(
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "auto"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "auto"),
        Provider("chatgpt", "ChatGPT plan", "https://api.openai.com/v1", "responses"),
        Provider("claude", "Claude", "https://api.anthropic.com/v1", "messages"),
        Provider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
        Provider("z_ai", "Z.AI", "https://api.z.ai/api/paas/v4"),
        Provider("moonshot", "Moonshot AI", "https://api.moonshot.ai/v1"),
        Provider("kimi", "Kimi", "https://api.moonshot.cn/v1"),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1"),
        Provider("demon_route", "Demon Route", "https://api.demonroute.com/v1"),
        Provider("command_code", "Command Code", "https://api.commandcode.ai/provider/v1", "auto"),
        Provider("opencode_go", "OpenCode Go", "https://opencode.ai/zen/go/v1", "auto"),
        Provider("openai_compatible", "OpenAI-compatible", ""),
    )
    val serverTools = linkedMapOf(
        "openrouter:web_search" to "Web search", "openrouter:web_fetch" to "Web fetch",
        "openrouter:datetime" to "Date and time", "openrouter:image_generation" to "Image generation",
        "openrouter:apply_patch" to "Apply patch", "openrouter:shell" to "Shell in OpenRouter sandbox",
        "openrouter:bash" to "Bash in OpenRouter sandbox", "openrouter:fusion" to "Fusion",
        "openrouter:advisor" to "Advisor", "openrouter:subagent" to "Subagent",
        "openrouter:tool_search" to "Tool search", "openrouter:experimental__search_models" to "Search models",
    )
    fun find(id: String) = providers.find { it.id == id }
    fun defaults(id: String): Account {
        val provider = requireNotNull(find(id)) { "Unknown cloud provider: $id" }
        return Account(name = provider.label, provider = id, baseUrl = provider.baseUrl, apiMode = provider.apiMode)
    }
}

/** Blocking network adapter. Call from a worker thread; cancellation closes the active socket. */
class CloudBackend(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).build(),
    private val chatGptToken: ((Account, Cancellation) -> String)? = null,
) : ChatBackend {
    private val modelMetadata = ConcurrentHashMap<String, JSONObject>()

    override fun models(account: Account, cancellation: Cancellation): List<String> {
        val result = linkedSetOf<String>()
        var cursor = ""
        repeat(20) {
            cancellation.check()
            val url = endpoint(account, "models").newBuilder()
            if (account.provider == "claude") {
                url.addQueryParameter("limit", "1000")
                if (cursor.isNotEmpty()) url.addQueryParameter("after_id", cursor)
            }
            val call = client.newCall(headers(account, "", cancellation).url(url.build()).get().build())
            cancellation.onCancel(call::cancel)
            val payload = call.execute().use { response ->
                val body = response.body ?: throw IOException("The provider returned an empty response.")
                if (!response.isSuccessful) throw failure(body.string(), response.code, account)
                JSONObject(body.string())
            }
            val data = payload.optJSONArray("data") ?: payload.optJSONArray("models") ?: JSONArray()
            for (index in 0 until data.length()) {
                val item = data.opt(index)
                val id = when (item) {
                    is JSONObject -> item.optString("id").ifBlank { item.optString("slug") }
                    is String -> item
                    else -> ""
                }.trim()
                if (id.isNotEmpty() && (item !is JSONObject || item.optString("visibility", "list") == "list")) {
                    result.add(id)
                    if (item is JSONObject) modelMetadata["${account.id}:$id"] = item
                }
            }
            if (!payload.optBoolean("has_more")) return result.sortedWith(String.CASE_INSENSITIVE_ORDER)
            val next = payload.optString("last_id")
            if (next.isBlank() || next == cursor) throw IOException("The provider returned an invalid model page cursor.")
            cursor = next
        }
        throw IOException("The provider model list exceeded 20 pages.")
    }

    /** Release time in epoch seconds from the last model list, or 0 when the provider does not report one. */
    fun created(account: Account, model: String): Long {
        val item = modelMetadata["${account.id}:$model"] ?: return 0
        return item.optLong("created").takeIf { it > 0 } ?: runCatching { java.time.Instant.parse(item.optString("created_at")).epochSecond }.getOrDefault(0)
    }

    override fun generate(request: ChatRequest, cancellation: Cancellation, onEvent: (GenerationEvent) -> Unit): String {
        cancellation.check()
        val account = request.account
        require(account.model.isNotBlank()) { "Choose a model before sending a message." }
        require(request.profile.maxTokens > 0) { "The output token limit must be positive." }
        require(request.profile.temperature.isFinite() && request.profile.temperature >= 0) { "Temperature must be a finite, nonnegative number." }
        require(!account.model.endsWith(":batch", true)) { "Batch models use a separate asynchronous API. Choose the model without :batch for chat." }
        val mode = protocol(account)
        val streaming = request.profile.streaming || account.provider == "chatgpt"
        val body = requestBody(request, mode, streaming, cancellation)
        val path = when (mode) { "messages" -> "messages"; "responses" -> "responses"; else -> "chat/completions" }
        val call = client.newCall(headers(account, mode, cancellation).url(endpoint(account, path))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        cancellation.onCancel(call::cancel)
        val answer = StringBuilder()
        val sources = linkedSetOf<String>()
        val pendingTools = linkedSetOf<String>()
        var finished = false
        fun emit(event: GenerationEvent) { cancellation.check(); answer.append(event.text); onEvent(event) }
        fun absorb(data: JSONObject, stream: Boolean) {
            if (data.has("error") && !data.isNull("error")) throw failure(data.toString(), null, account)
            when (mode) {
                "responses" -> {
                    when (data.optString("type")) {
                        "response.output_text.delta" -> emit(GenerationEvent(text = data.optString("delta")))
                        "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> emit(GenerationEvent(reasoning = data.optString("delta")))
                        "response.completed" -> finished = true
                        "response.incomplete" -> { finished = true; emit(GenerationEvent(status = "Response incomplete: " + (data.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason") ?: "unknown reason"))) }
                        "response.failed" -> throw failure((data.optJSONObject("response") ?: data).toString(), null, account)
                        "error" -> throw failure(data.toString(), null, account)
                        "response.output_text.annotation.added" -> citation(data.optJSONObject("annotation"), sources)
                        "response.output_item.added" -> data.optJSONObject("item")?.let { item ->
                            if (item.optString("type").endsWith("_call")) emit(GenerationEvent(status = "Remote tool: ${item.optString("type")}"))
                        }
                    }
                    if (!stream) {
                        val status = data.optString("status")
                        if (status == "failed") throw failure(data.toString(), null, account)
                        emit(GenerationEvent(text = data.optString("output_text").ifBlank {
                            objects(data.optJSONArray("output")).joinToString("") { contentText(it.optJSONArray("content")) }
                        }))
                        finished = true
                        if (status == "incomplete") emit(GenerationEvent(status = "Response reached its limit before completion."))
                    }
                }
                "messages" -> {
                    when (data.optString("type")) {
                        "content_block_delta" -> data.optJSONObject("delta")?.let { delta -> emit(GenerationEvent(text = delta.optString("text"), reasoning = delta.optString("thinking"))) }
                        "message_delta" -> if (data.optJSONObject("delta")?.optString("stop_reason") == "max_tokens") emit(GenerationEvent(status = "Response reached the output token limit."))
                        "message_stop" -> finished = true
                        "error" -> throw failure(data.toString(), null, account)
                    }
                    if (!stream) { emit(GenerationEvent(text = contentText(data.optJSONArray("content")))); finished = true }
                }
                else -> {
                    val choice = data.optJSONArray("choices")?.optJSONObject(0) ?: return
                    if (choice.has("error")) throw failure(choice.toString(), null, account)
                    val finish = choice.optString("finish_reason")
                    if (finish.isNotBlank() && finish != "null") finished = true
                    if (finish == "error") throw IOException("The provider ended the response with an error.")
                    if (finish == "length") emit(GenerationEvent(status = "Response reached the output token limit."))
                    if (finish == "content_filter") emit(GenerationEvent(status = "The provider filtered this response."))
                    val delta = choice.optJSONObject(if (stream) "delta" else "message") ?: return
                    val reasoning = delta.optString("reasoning_content").ifBlank { delta.optString("reasoning") }.ifBlank {
                        objects(delta.optJSONArray("reasoning_details")).filter { it.optString("type") != "reasoning.encrypted" }.joinToString("") { it.optString("text").ifBlank { it.optString("summary") } }
                    }
                    val content = delta.opt("content")
                    emit(GenerationEvent(text = if (content is String) content else contentText(content as? JSONArray), reasoning = reasoning))
                    if (delta.optString("refusal").isNotBlank()) emit(GenerationEvent(text = delta.getString("refusal")))
                    objects(delta.optJSONArray("annotations")).forEach { citation(it, sources) }
                    objects(delta.optJSONArray("tool_calls")).forEach { tool ->
                        val name = tool.optJSONObject("function")?.optString("name").orEmpty().ifBlank { tool.optString("type") }
                        if (name.isNotBlank() && pendingTools.add(name)) emit(GenerationEvent(status = "Remote tool requested: $name"))
                    }
                    if (!stream) finished = true
                }
            }
        }
        call.execute().use { response ->
            val responseBody = response.body ?: throw IOException("The provider returned an empty response.")
            if (!response.isSuccessful) throw failure(responseBody.string(), response.code, account)
            if (account.provider == "openrouter") {
                val cache = response.header("X-OpenRouter-Cache-Status").orEmpty().uppercase()
                if (response.header("X-OpenRouter-Cache-Age") != null || cache !in setOf("", "MISS", "BYPASS")) throw IOException("Cached OpenRouter response rejected.")
            }
            if (!streaming) absorb(JSONObject(responseBody.string()), false)
            else {
                val source = responseBody.source()
                val event = StringBuilder()
                fun dispatch() {
                    if (event.isEmpty()) return
                    val data = event.toString().trimEnd('\n')
                    event.clear()
                    if (data == "[DONE]") finished = true else absorb(JSONObject(data), true)
                }
                while (!source.exhausted()) {
                    cancellation.check()
                    val line = source.readUtf8Line() ?: break
                    require(line.length <= 1_048_576 && event.length + line.length <= 1_048_576) { "The provider sent an oversized stream event." }
                    if (line.isEmpty()) dispatch()
                    else if (line.startsWith("data:")) event.append(line.substring(5).removePrefix(" ")).append('\n')
                }
                dispatch()
            }
        }
        cancellation.check()
        if (!finished) throw IOException("The answer stopped before it was complete. The partial response may be incomplete.")
        if (answer.isBlank()) throw IOException(if (pendingTools.isEmpty()) "The provider completed without returning text." else "The model requested ${pendingTools.joinToString()}, but returned no text. This chat does not execute client tools.")
        if (sources.isNotEmpty()) emit(GenerationEvent(text = "\n\nSources:\n" + sources.joinToString("\n")))
        return answer.toString()
    }

    private fun requestBody(request: ChatRequest, mode: String, streaming: Boolean, cancellation: Cancellation): JSONObject {
        val account = request.account
        val profile = request.profile
        val body = jsonObject(account.customBody, "Custom body")
        val messages = buildList {
            if (profile.systemPrompt.isNotBlank()) add(ChatMessage("system", profile.systemPrompt))
            addAll(request.messages)
        }
        val system = messages.filter { it.role in setOf("system", "developer") }.joinToString("\n\n") { it.content }
        val wire = JSONArray()
        for (message in messages) {
            cancellation.check()
            require(message.role in setOf("system", "developer", "user", "assistant")) { "Unsupported message role: ${message.role}" }
            if (mode == "messages" && message.role in setOf("system", "developer")) continue
            wire.put(JSONObject().put("role", message.role).put("content", content(message, account, mode, cancellation)))
        }
        val model = account.model.removePrefix(if (account.provider == "command_code") "command-code/" else if (account.provider == "opencode_go") "opencode-go/" else "")
        body.put("model", model).put("stream", streaming)
        if (mode == "responses") body.put("input", wire).put("store", false) else body.put("messages", wire)
        if (mode == "messages" && system.isNotBlank()) body.put("system", system)
        if (account.provider != "chatgpt") {
            val openAiReasoning = account.provider == "openai" && (model.startsWith("o") || model.startsWith("gpt-5") || model.startsWith("gpt-6"))
            if (!openAiReasoning) body.put("temperature", profile.temperature)
            body.put(if (mode == "responses") "max_output_tokens" else if (openAiReasoning && mode == "chat_completions") "max_completion_tokens" else "max_tokens", profile.maxTokens)
        } else { body.remove("temperature"); body.remove("max_tokens"); body.remove("max_output_tokens") }
        if (profile.reasoningEffort.isNotEmpty()) {
            require(profile.reasoningEffort in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) { "Unknown reasoning effort." }
            when {
                account.provider == "openrouter" -> body.put("reasoning", if (profile.reasoningEffort == "none") JSONObject().put("enabled", false) else JSONObject().put("effort", profile.reasoningEffort))
                mode == "responses" -> body.put("reasoning", JSONObject().put("effort", profile.reasoningEffort).put("summary", "auto"))
                mode == "chat_completions" -> body.put("reasoning_effort", profile.reasoningEffort)
                else -> throw IllegalArgumentException("Reasoning effort is not supported by this Messages adapter; clear it in the profile.")
            }
        }
        if (profile.serverTools.isNotEmpty()) {
            require(account.provider == "openrouter") { "These server tools run at OpenRouter. Choose an OpenRouter account or clear them in the profile." }
            require(profile.serverTools.all { it in ProviderCatalog.serverTools }) { "The profile contains an unknown OpenRouter tool." }
            body.put("tools", JSONArray(profile.serverTools.distinct().map { JSONObject().put("type", it) }))
        }
        if (request.jsonOutput) when (mode) {
            "responses" -> body.put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
            "chat_completions" -> body.put("response_format", JSONObject().put("type", "json_object"))
            else -> throw IllegalArgumentException("JSON output is not supported by this Messages adapter.")
        }
        return body
    }

    private fun content(message: ChatMessage, account: Account, mode: String, cancellation: Cancellation): Any {
        if (message.attachments.isEmpty()) return message.content
        require(message.role == "user") { "Attachments must belong to a user message." }
        val blocks = JSONArray()
        fun text(value: String) { blocks.put(JSONObject().put("type", if (mode == "responses") "input_text" else "text").put("text", value)) }
        if (message.content.isNotEmpty()) text(message.content)
        var total = 0L
        for (attachment in message.attachments) {
            cancellation.check()
            val file = File(attachment.path)
            require(file.isFile) { "Attachment ${attachment.name} is no longer available. Attach it again." }
            total += file.length()
            require(total <= 20 * 1024 * 1024) { "Attachments in one message must total 20 MB or less." }
            val mime = attachment.mimeType.lowercase()
            val isText = mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/javascript", "application/x-yaml")
            if (isText) {
                require(file.length() <= 1024 * 1024) { "Text attachment ${attachment.name} must be 1 MB or less." }
                val decoded = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.readBytes())).toString() }
                    catch (_: Exception) { throw IllegalArgumentException("Text attachment ${attachment.name} must use UTF-8 encoding.") }
                text("Attachment: ${attachment.name}\n$decoded")
                continue
            }
            require(mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf")) { "Unsupported attachment type: $mime. Use UTF-8 text, an image, or PDF." }
            checkMultimodal(account, mime)
            val encoded = Base64.getEncoder().encodeToString(file.readBytes())
            val dataUrl = "data:$mime;base64,$encoded"
            val block = when {
                mode == "messages" -> JSONObject().put("type", if (mime == "application/pdf") "document" else "image").put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", encoded))
                mode == "responses" && mime == "application/pdf" -> JSONObject().put("type", "input_file").put("filename", attachment.name).put("file_data", dataUrl)
                mode == "responses" -> JSONObject().put("type", "input_image").put("image_url", dataUrl)
                mime == "application/pdf" -> JSONObject().put("type", "file").put("file", JSONObject().put("filename", attachment.name).put("file_data", dataUrl))
                else -> JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl))
            }
            blocks.put(block)
        }
        return blocks
    }

    private fun checkMultimodal(account: Account, mime: String) {
        val model = account.model.lowercase()
        when (account.provider) {
            "openrouter" -> {
                val modalities = modelMetadata["${account.id}:${account.model}"]?.optJSONObject("architecture")?.optJSONArray("input_modalities")
                require(modalities != null && (0 until modalities.length()).any { modalities.optString(it) == if (mime == "application/pdf") "file" else "image" }) {
                    "Model capability for $mime is unavailable or unsupported. Refresh the model list and choose a model that accepts this attachment type."
                }
            }
            "claude" -> require(model.startsWith("claude-") && !model.startsWith("claude-2")) { "Choose a Claude model with document and vision support." }
            "openai" -> require(model.startsWith("gpt-4o") || model.startsWith("gpt-4.1") || model.startsWith("gpt-5") || model.startsWith("gpt-6") || model.startsWith("o3") || model.startsWith("o4")) { "Choose an OpenAI model with vision support for this attachment." }
            "gemini" -> require(mime.startsWith("image/") && model.startsWith("gemini-")) { "Gemini's OpenAI-compatible adapter accepts images; use another provider for PDF attachments." }
            else -> throw IllegalArgumentException("${ProviderCatalog.find(account.provider)?.label ?: account.provider} has no verified $mime capability in this adapter. Use a text attachment or a supported vision provider.")
        }
    }

    private fun protocol(account: Account): String {
        if (account.provider == "claude") return "messages"
        if (account.provider == "chatgpt") return "responses"
        if (account.apiMode != "auto") { require(account.apiMode in setOf("responses", "messages", "chat_completions")) { "Unknown API mode." }; return account.apiMode }
        val model = account.model.removePrefix("command-code/").removePrefix("opencode-go/")
        return when {
            account.provider == "openai" -> "responses"
            account.provider == "command_code" && (model.startsWith("claude-") || model.startsWith("anthropic/")) -> "messages"
            account.provider == "opencode_go" && listOf("gpt-", "grok-", "muse-").any(model::startsWith) -> "responses"
            account.provider == "opencode_go" && listOf("minimax-", "qwen").any(model::startsWith) -> "messages"
            else -> "chat_completions"
        }
    }

    private fun endpoint(account: Account, path: String): HttpUrl {
        val provider = requireNotNull(ProviderCatalog.find(account.provider)) { "Unknown cloud provider: ${account.provider}" }
        val base = if (account.provider == "openai_compatible") account.baseUrl else provider.baseUrl
        require(base.isNotBlank()) { "Enter a base URL for the compatible service." }
        val url = (base.trimEnd('/') + "/" + path).toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "Put credentials in the API key or headers fields, not the URL." }
        val local = url.host == "localhost" || url.host == "::1" || url.host.startsWith("127.") || url.host.startsWith("192.168.") || url.host.startsWith("10.") || Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..*").matches(url.host)
        require(url.isHttps || local) { "Remote providers require an HTTPS URL to protect messages and credentials." }
        return url
    }

    private fun headers(account: Account, mode: String, cancellation: Cancellation): Request.Builder {
        val builder = Request.Builder().header("Accept", "application/json, text/event-stream")
        val custom = jsonObject(account.customHeaders, "Custom headers")
        for (key in custom.keys()) { require(custom.get(key) is String) { "Custom header values must be strings." }; builder.header(key, custom.getString(key)) }
        if (account.provider == "chatgpt") {
            val token = chatGptToken?.invoke(account, cancellation) ?: throw IllegalStateException("ChatGPT plan requires Continue with ChatGPT sign-in. An OpenAI API key cannot use a ChatGPT subscription.")
            require(token.isNotBlank()) { "ChatGPT sign-in returned no access token. Sign in again." }
            builder.header("Authorization", "Bearer $token")
        } else if (account.apiKey.isNotBlank()) {
            if (account.provider == "claude") builder.header("x-api-key", account.apiKey) else builder.header("Authorization", "Bearer ${account.apiKey}")
        } else require(account.provider == "openai_compatible") { "Enter an API key for ${ProviderCatalog.find(account.provider)?.label}." }
        if (mode == "messages" || account.provider == "claude") builder.header("anthropic-version", "2023-06-01")
        if (account.provider == "openrouter") builder.header("X-OpenRouter-Cache", "false").header("Cache-Control", "no-store, no-cache, max-age=0").header("Pragma", "no-cache")
        return builder
    }

    private fun jsonObject(text: String, label: String): JSONObject = try { JSONObject(text.ifBlank { "{}" }) }
        catch (_: Exception) { throw IllegalArgumentException("$label must be a JSON object.") }
    private fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
    private fun contentText(array: JSONArray?): String = objects(array).filter { it.optString("type") in setOf("text", "output_text") }.joinToString("") { it.optString("text") }
    private fun citation(annotation: JSONObject?, sources: MutableSet<String>) {
        val citation = annotation?.optJSONObject("url_citation") ?: annotation ?: return
        val url = citation.optString("url")
        if (url.startsWith("https://") || url.startsWith("http://")) sources.add(citation.optString("title").ifBlank { "Source" } + ": " + url)
    }
    private fun failure(raw: String, status: Int?, account: Account): IOException {
        val payload = runCatching { JSONObject(raw) }.getOrNull()
        val error = payload?.optJSONObject("error") ?: payload
        val message = error?.optString("message")?.ifBlank { error.optString("detail") }?.ifBlank { error.optString("code") }
            ?.takeIf { it.isNotBlank() } ?: "The provider request failed."
        val safe = if (account.apiKey.isNotBlank()) message.replace(account.apiKey, "[redacted]") else message
        return IOException((status?.let { "HTTP $it: " } ?: "") + safe.take(1000))
    }
}
