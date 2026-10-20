package org.blindpilot.android.backends

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.blindpilot.android.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class HermesQuestion(
    val kind: String,
    val requestId: String,
    val sessionId: String,
    val text: String,
    val choices: List<String>,
    val secret: Boolean = false,
    val multiSelect: Boolean = false,
)

data class HermesSession(
    val id: String,
    val title: String,
    val preview: String,
    val messageCount: Int,
    val source: String,
    val startedAt: Double,
    val active: Boolean,
)

/** Blocking network adapter: callers run it off the main thread, including the question callback. */
class HermesBackend(
    private val onQuestion: (HermesQuestion) -> String = { "" },
    private val onSession: (String) -> Unit = {},
    private val client: OkHttpClient = OkHttpClient(),
    private val allowInsecureLocalhost: Boolean = false,
) : ChatBackend {
    override fun generate(request: ChatRequest, cancellation: Cancellation, onEvent: (GenerationEvent) -> Unit): String {
        val message = request.messages.lastOrNull { it.role == "user" }
            ?: throw IOException("Write a message for Hermes first.")
        Connection(request.account, cancellation, onEvent).use { connection ->
            connection.open()
            val session = connection.session(request.profile)
            // A resumed running turn is observed, never submitted again after reconnecting.
            if (session.optBoolean("running")) {
                onEvent(GenerationEvent(status = "Attached to the running Hermes turn."))
                return connection.consume()
            }
            if (message.content.trim().startsWith("/") && message.attachments.isEmpty()) {
                val name = message.content.trim().substringBefore(' ').substringBefore('\n')
                val known = connection.commands(name)
                if (known.any { it.equals(name, ignoreCase = true) }) {
                    return connection.slash(message.content.trim())
                }
            }
            if (request.account.sessionId.isNotBlank() && request.account.model.isNotBlank()) {
                val (provider, model) = splitModel(request.account.model)
                val command = "/model $model" + (if (provider.isNotBlank()) " --provider $provider" else "") + " --session"
                connection.slash(command)
            }
            val uploaded = message.attachments.map { attachment ->
                cancellation.check()
                val file = File(attachment.path)
                if (!file.isFile || !file.canRead()) throw IOException("The attached file could not be read.")
                if (file.length() !in 1..MAX_ATTACHMENT_BYTES) throw IOException("Hermes attachments must contain between 1 byte and 25 MB.")
                val bytes = file.readBytes()
                if (bytes.size.toLong() !in 1..MAX_ATTACHMENT_BYTES) throw IOException("The attached file changed or exceeds 25 MB.")
                val name = attachment.name.replace('\\', '/').substringAfterLast('/').ifBlank { file.name }
                onEvent(GenerationEvent(status = "Sending $name to remote Hermes."))
                val data = "data:${attachment.mimeType.ifBlank { "application/octet-stream" }};base64," + Base64.getEncoder().encodeToString(bytes)
                val result = connection.call("file.attach", obj("session_id" to connection.sessionId, "name" to name, "data_url" to data), 300)
                val path = result.optString("path")
                if (path.isBlank()) throw IOException("Hermes did not confirm the uploaded file path.")
                "$name: $path"
            }
            var text = message.content
            if (uploaded.isNotEmpty()) {
                val plural = uploaded.size > 1
                val note = "Attached ${if (plural) "files" else "file"} (uploaded to this machine, please read ${if (plural) "them" else "it"}):\n" + uploaded.joinToString("\n")
                text = if (text.isBlank()) note else "$text\n\n$note"
            }
            connection.call("prompt.submit", obj("session_id" to connection.sessionId, "text" to text), 120)
            return connection.consume()
        }
    }

    override fun models(account: Account, cancellation: Cancellation): List<String> = Connection(account, cancellation).use { connection ->
        connection.open()
        connection.call("model.options").optJSONArray("providers").objects().flatMap { provider ->
            val slug = provider.optString("slug").trim()
            if (slug.isBlank() || provider.opt("authenticated") == false) emptyList()
            else provider.optJSONArray("models").strings().filter { it.isNotBlank() }.map { "$slug · $it" }
        }.ifEmpty { throw IOException("Hermes reported no usable models.") }
    }

    fun listSessions(account: Account, cancellation: Cancellation): List<HermesSession> = Connection(account, cancellation).use { connection ->
        connection.open()
        val rows = connection.call("session.list", obj("limit" to 200)).optJSONArray("sessions").objects()
        val active = try {
            connection.call("session.active_list").optJSONArray("sessions").objects().map { it.optString("session_key") }.toSet()
        } catch (error: IOException) {
            cancellation.check()
            emptySet()
        }
        rows.filter { it.optString("source").lowercase() !in setOf("kanban", "tool") }.map { row ->
            HermesSession(row.optString("id"), row.optString("title"), row.optString("preview"), row.optInt("message_count"), row.optString("source"), row.optDouble("started_at", 0.0), row.optString("id") in active)
        }
    }

    /** Resume takes ownership of the gateway stream; do not call alongside an active generation. */
    fun history(account: Account, cancellation: Cancellation): List<ChatMessage> = Connection(account, cancellation).use { connection ->
        require(account.sessionId.isNotBlank()) { "Choose a Hermes session first." }
        connection.open()
        connection.session(omitMessages = false).optJSONArray("messages").objects().mapNotNull { row ->
            val role = row.optString("role")
            val content = row.optString("text").ifBlank {
                when (val value = row.opt("content")) {
                    is String -> value
                    is JSONArray -> value.objects().mapNotNull { it.optString("text").takeIf(String::isNotBlank) }.joinToString("\n")
                    else -> ""
                }
            }
            if (content.isBlank()) null else ChatMessage(role, content)
        }
    }

    fun slashCommands(account: Account, cancellation: Cancellation, text: String = "/"): List<String> = Connection(account, cancellation).use { connection ->
        connection.open()
        connection.commands(text)
    }

    fun executeSlash(account: Account, command: String, cancellation: Cancellation): String = Connection(account, cancellation).use { connection ->
        require(command.trim().startsWith("/")) { "Hermes commands start with a slash." }
        connection.open()
        val result = connection.session()
        if (result.optBoolean("running")) throw IOException("Stop the running Hermes turn before executing a command.")
        connection.slash(command)
    }

    private inner class Connection(
        val account: Account,
        val cancellation: Cancellation,
        val onEvent: (GenerationEvent) -> Unit = {},
    ) : Closeable {
        private val frames = LinkedBlockingQueue<JSONObject>()
        private val ids = AtomicInteger()
        private val secrets = mutableListOf(account.apiKey)
        @Volatile private var failure: String? = null
        @Volatile private var socket: WebSocket? = null
        @Volatile var sessionId = ""
            private set
        private val answer = StringBuilder()
        private var finalAnswer: String? = null
        private var complete = false

        fun open() {
            cancellation.check()
            val endpoint = endpoint(account.baseUrl)
            val local = endpoint.host in setOf("localhost", "127.0.0.1", "::1")
            if (endpoint.scheme != "https" && account.apiKey.isNotEmpty() && !(allowInsecureLocalhost && local)) {
                throw IOException("Hermes credentials require a wss:// address. Cleartext is allowed only for explicitly configured loopback development.")
            }
            val jar = mutableListOf<Cookie>()
            val transport = client.newBuilder().retryOnConnectionFailure(false).readTimeout(0, TimeUnit.SECONDS)
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        synchronized(jar) {
                            cookies.forEach { cookie ->
                                jar.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                                jar.add(cookie)
                            }
                        }
                    }
                    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(jar) {
                        jar.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
                    }
                }).build()
            val upgrade = endpoint.newBuilder().removeAllQueryParameters("token").removeAllQueryParameters("ticket")
            when (account.credential) {
                "password" -> {
                    if (account.apiKey.isBlank()) throw IOException("Enter the Hermes password.")
                    val base = endpoint.newBuilder().query(null).encodedPath(endpoint.encodedPath.removeSuffix("/api/ws")).build().toString().trimEnd('/')
                    fun post(path: String, body: JSONObject?): JSONObject {
                        cancellation.check()
                        val request = Request.Builder().url(base + path).post((body?.toString() ?: "").toRequestBody(JSON)).build()
                        val call = transport.newCall(request)
                        cancellation.onCancel { call.cancel() }
                        try {
                            call.execute().use { response ->
                                if (!response.isSuccessful) {
                                    val reason = if (path.endsWith("password-login") && response.code in listOf(401, 403)) "Hermes rejected that username and password."
                                    else if (response.code == 400) "Hermes refused the address. Check dashboard.public_url on its host."
                                    else "Hermes authentication failed (HTTP ${response.code})."
                                    throw IOException(reason)
                                }
                                return JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
                            }
                        } catch (error: IOException) {
                            cancellation.check()
                            throw IOException("Hermes sign-in failed. Check the address, TLS certificate, and credentials.")
                        }
                    }
                    post("/auth/password-login", obj("provider" to "basic", "username" to account.username, "password" to account.apiKey))
                    val ticket = post("/api/auth/ws-ticket", null).optString("ticket")
                    if (ticket.isBlank()) throw IOException("Hermes signed in but issued no WebSocket ticket.")
                    secrets.add(ticket)
                    upgrade.addQueryParameter("ticket", ticket)
                }
                "token" -> if (account.apiKey.isNotBlank()) upgrade.addQueryParameter("token", account.apiKey)
                else -> throw IOException("Choose Hermes session token or password authentication.")
            }
            socket = transport.newWebSocket(Request.Builder().url(upgrade.build()).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try { frames.offer(JSONObject(text)) } catch (_: Exception) { failure = "Hermes sent an invalid gateway message." }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = if (response != null) "Hermes refused the connection (HTTP ${response.code}). Check its authentication mode and address."
                    else "Hermes disconnected. Check the network and its TLS certificate. The prompt was not resent."
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    failure = "Hermes disconnected. The prompt was not resent."
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { failure = "Hermes disconnected. The prompt was not resent." }
            })
            cancellation.onCancel {
                if (sessionId.isNotBlank()) send("session.interrupt", obj("session_id" to sessionId), checkCancellation = false)
                socket?.close(1000, "Stopped")
            }
            val deadline = deadline(60)
            while (true) {
                val frame = next(deadline)
                if (frame.optJSONObject("params")?.optString("type") == "gateway.ready") break
                handle(frame)
            }
            // Older gateways may reject or ignore this optional capability advertisement.
            val id = send("client.capabilities", obj("server_requests" to true))
            try { await(id, 5) } catch (error: IOException) { cancellation.check(); if (failure != null) throw error }
        }

        fun session(profile: Profile = Profile(), omitMessages: Boolean = true): JSONObject {
            val result = if (account.sessionId.isNotBlank()) {
                call("session.resume", obj("session_id" to account.sessionId, "omit_messages" to omitMessages), 120)
            } else {
                val params = JSONObject()
                val (provider, model) = splitModel(account.model)
                if (model.isNotBlank()) params.put("model", model)
                if (provider.isNotBlank()) params.put("provider", provider)
                if (profile.reasoningEffort.isNotBlank()) params.put("reasoning_effort", profile.reasoningEffort)
                call("session.create", params, 120)
            }
            sessionId = result.optString("session_id")
            if (sessionId.isBlank()) throw IOException("Hermes did not return a session id.")
            val durable = listOf("session_key", "resumed", "stored_session_id").firstNotNullOfOrNull { result.optString(it).takeIf(String::isNotBlank) }
                ?: account.sessionId.ifBlank { sessionId }
            onSession(durable)
            result.optJSONArray("open_requests").objects().forEach { handle(it) }
            return result
        }

        fun commands(text: String) = call("complete.slash", obj("text" to text), 30).optJSONArray("items").objects()
            .map { it.optString("text").trim() }.filter { it.isNotBlank() }

        fun slash(command: String): String {
            cancellation.check()
            onEvent(GenerationEvent(status = "Running Hermes command ${command.substringBefore(' ')}."))
            return call("slash.exec", obj("session_id" to sessionId, "command" to command), 600).optString("output").ifBlank { "Hermes command finished with no output." }
        }

        fun call(method: String, params: JSONObject = JSONObject(), seconds: Long = 60): JSONObject {
            val response = await(send(method, params), seconds)
            if (response.has("error")) throw IOException(safe("Hermes refused $method: " + response.optJSONObject("error")?.optString("message", "request failed")))
            return response.optJSONObject("result") ?: JSONObject()
        }

        private fun send(method: String, params: JSONObject, checkCancellation: Boolean = true): Int {
            if (checkCancellation) cancellation.check()
            val id = ids.incrementAndGet()
            if (socket?.send(obj("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params).toString()) != true) {
                if (checkCancellation) throw IOException("Hermes connection closed. The prompt was not resent.")
            }
            return id
        }

        private fun await(id: Int, seconds: Long): JSONObject {
            val end = deadline(seconds)
            while (true) {
                val frame = next(end)
                if (frame.opt("id") == id && !frame.has("method")) return frame
                handle(frame)
            }
        }

        private fun next(end: Long): JSONObject {
            while (true) {
                cancellation.check()
                frames.poll()?.let { return it }
                failure?.let { throw IOException(it) }
                if (System.nanoTime() >= end) throw IOException("Hermes did not answer in time. The prompt was not resent.")
                try { frames.poll(100, TimeUnit.MILLISECONDS)?.let { return it } }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); cancellation.check() }
            }
        }

        fun consume(): String {
            var lastProgress = System.nanoTime()
            var end = deadline(900)
            while (!complete) {
                handle(next(end))
                if (System.nanoTime() - lastProgress > TimeUnit.SECONDS.toNanos(30)) {
                    onEvent(GenerationEvent(status = "Hermes is still working."))
                    lastProgress = System.nanoTime()
                }
                // An active stream can run longer than the idle timeout.
                end = deadline(900)
            }
            return finalAnswer ?: answer.toString()
        }

        private fun handle(frame: JSONObject) {
            val method = frame.optString("method")
            val params = frame.optJSONObject("params") ?: return
            val incomingSession = params.optString("session_id")
            if (sessionId.isNotBlank() && incomingSession.isNotBlank() && incomingSession != sessionId) return
            if (method.isNotBlank() && method != "event" && frame.has("id")) {
                question(method, params, frame.get("id"))
                return
            }
            val payload = params.optJSONObject("payload") ?: JSONObject()
            when (val kind = params.optString("type")) {
                "message.delta" -> payload.optString("text").takeIf(String::isNotEmpty)?.let { answer.append(it); onEvent(GenerationEvent(text = it)) }
                "thinking.delta", "reasoning.available" -> payload.optString("text").takeIf(String::isNotEmpty)?.let { onEvent(GenerationEvent(reasoning = it)) }
                "status.update", "notification.show" -> onEvent(GenerationEvent(status = safe(firstText(payload, "text", "message", "kind"))))
                "tool.start", "tool.complete" -> {
                    val name = payload.optString("name", "tool")
                    val summary = firstText(payload, "summary", "context", "args_text", "result_text").take(4000)
                    onEvent(GenerationEvent(status = safe("$name${if (summary.isBlank()) "" else ": $summary"}")))
                }
                "approval.request", "clarify.request", "sudo.request", "secret.request" -> question(kind.removeSuffix(".request"), payload, null)
                "message.complete" -> {
                    when (payload.optString("status")) {
                        "interrupted", "cancelled" -> throw IOException("Hermes stopped before finishing the answer.")
                        "error", "failed" -> throw IOException(safe(firstText(payload, "error", "text").ifBlank { "Hermes turn failed." }))
                    }
                    finalAnswer = payload.optString("text").takeIf(String::isNotBlank)
                    complete = true
                }
                "error" -> throw IOException(safe(firstText(payload, "message", "error").ifBlank { "Hermes turn failed." }))
                else -> if (kind.startsWith("subagent.")) {
                    onEvent(GenerationEvent(status = safe("Hermes subagent ${kind.substringAfter('.')}: " + firstText(payload, "text", "summary", "goal", "tool_name"))))
                }
            }
        }

        private fun question(kind: String, payload: JSONObject, responseId: Any?) {
            val requestId = responseId?.toString() ?: payload.optString("request_id")
            if (requestId.isBlank()) return
            fun reply(result: JSONObject) {
                cancellation.check()
                if (responseId != null) {
                    if (socket?.send(obj("jsonrpc" to "2.0", "id" to responseId, "result" to result).toString()) != true) throw IOException("Hermes disconnected before the answer was sent.")
                } else {
                    result.put("request_id", requestId)
                    send("$kind.respond", result)
                }
            }
            fun ask(entry: JSONObject, secret: Boolean = false, choices: List<String> = entry.optJSONArray("choices").strings()): String {
                cancellation.check()
                val text = firstText(entry, "question", "prompt", "message", "command", "summary").ifBlank {
                    if (secret) "Hermes needs a secret value." else "Hermes needs your answer."
                }
                val value = onQuestion(HermesQuestion(kind, requestId, sessionId, text, choices, secret, entry.optBoolean("multi_select") && choices.isNotEmpty()))
                if (secret && value.isNotBlank()) secrets.add(value)
                cancellation.check()
                return value
            }
            when (kind) {
                "approval" -> {
                    val choices = payload.optJSONArray("choices").strings().ifEmpty { listOf("once", "session", "always", "deny") }
                    val value = ask(payload, choices = choices)
                    reply(obj("choice" to (choices.firstOrNull { it.equals(value.trim(), true) } ?: "deny")))
                }
                "sudo", "secret" -> {
                    val value = ask(payload, secret = true)
                    reply(obj((if (responseId == null && kind == "sudo") "password" else "value") to value))
                }
                "clarify" -> {
                    val batch = payload.optJSONArray("questions").objects()
                    if (batch.isEmpty()) reply(obj("answer" to ask(payload)))
                    else if (responseId != null) {
                        val answers = JSONObject()
                        batch.forEach { entry -> answers.put(entry.optString("qid"), ask(entry)) }
                        reply(obj("answers" to answers))
                    } else batch.forEach { entry -> reply(obj("question_id" to entry.optString("qid"), "answer" to ask(entry))) }
                }
                else -> {
                    cancellation.check()
                    if (responseId != null) socket?.send(obj("jsonrpc" to "2.0", "id" to responseId, "error" to obj("code" to -32601, "message" to "Unsupported server request")).toString())
                }
            }
        }

        private fun safe(text: String): String = secrets.filter { it.isNotBlank() }.fold(text) { value, secret -> value.replace(secret, "[redacted]") }
        override fun close() { socket?.close(1000, "Done"); socket = null }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
        fun normalizedEndpoint(value: String): String = endpoint(value).toString().replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        private fun endpoint(value: String): HttpUrl {
            val input = value.trim()
            if (input.isBlank()) throw IOException("Enter the Hermes gateway address.")
            var address = when {
                input.startsWith("wss://", true) -> "https://" + input.substring(6)
                input.startsWith("ws://", true) -> "http://" + input.substring(5)
                "://" !in input -> "https://$input"
                else -> input
            }
            val parsed = try { address.toHttpUrl() } catch (_: IllegalArgumentException) { throw IOException("Enter a valid Hermes gateway address.") }
            if (parsed.username.isNotBlank() || parsed.password.isNotBlank() || parsed.fragment != null || parsed.query != null) throw IOException("Put Hermes credentials in their account fields, and use an address without a query or fragment.")
            val path = parsed.encodedPath.trimEnd('/').removeSuffix("/api/ws") + "/api/ws"
            return parsed.newBuilder().encodedPath(path).build()
        }
        private fun splitModel(value: String): Pair<String, String> = if (" · " in value) value.substringBefore(" · ").trim() to value.substringAfter(" · ").trim() else "" to value.trim()
        private fun obj(vararg values: Pair<String, Any>): JSONObject = JSONObject().apply { values.forEach { (key, value) -> put(key, value) } }
        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { (opt(it) as? String)?.takeIf(String::isNotBlank) }
        private fun firstText(value: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull { (value.opt(it) as? String)?.takeIf(String::isNotBlank) }.orEmpty()
        private fun deadline(seconds: Long) = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    }
}
