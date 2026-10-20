package org.blindpilot.android.core

import java.io.InterruptedIOException
import java.util.UUID

data class Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New account",
    val provider: String = "openai_compatible",
    val baseUrl: String = "",
    val apiKey: String = "",
    val apiMode: String = "chat_completions",
    val model: String = "",
    val username: String = "",
    val credential: String = "token",
    val sessionId: String = "",
    val customHeaders: String = "{}",
    val customBody: String = "{}",
)

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default",
    val systemPrompt: String = "You are a helpful assistant. The user is blind; describe useful details in words.",
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
    val contextSize: Int = 4096,
    val reasoningEffort: String = "",
    val serverTools: List<String> = emptyList(),
    val streaming: Boolean = true,
    /** Applied when the profile is chosen; blank keeps the current account or model. */
    val accountId: String = "",
    val model: String = "",
)

data class Attachment(val name: String, val mimeType: String, val path: String)
data class ChatMessage(val role: String, val content: String, val attachments: List<Attachment> = emptyList())
data class ChatRequest(val account: Account, val profile: Profile, val messages: List<ChatMessage>, val jsonOutput: Boolean = false)
data class GenerationEvent(val text: String = "", val reasoning: String = "", val status: String = "")

class Cancellation {
    @Volatile var cancelled: Boolean = false
        private set
    private val handlers = mutableListOf<() -> Unit>()
    fun cancel() {
        val callbacks = synchronized(this) { if (cancelled) return; cancelled = true; handlers.toList().also { handlers.clear() } }
        callbacks.forEach { runCatching { it() } }
    }
    fun onCancel(handler: () -> Unit) {
        val invoke = synchronized(this) { if (cancelled) true else { handlers.add(handler); false } }
        if (invoke) handler()
    }
    fun check() { if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Stopped") }
}

interface ChatBackend {
    fun generate(request: ChatRequest, cancellation: Cancellation, onEvent: (GenerationEvent) -> Unit): String
    fun models(account: Account, cancellation: Cancellation): List<String>
}
