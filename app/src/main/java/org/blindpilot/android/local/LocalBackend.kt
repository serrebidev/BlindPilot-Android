package org.blindpilot.android.local

import android.app.ActivityManager
import android.content.Context
import org.blindpilot.android.core.*
import java.io.Closeable
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicLong

class LocalBackend(context: Context) : ChatBackend, Closeable {
    private val context = context.applicationContext
    private val manager = ModelManager(this.context)
    private var handle = 0L
    private var loadedPath = ""

    override fun models(account: Account, cancellation: Cancellation): List<String> {
        cancellation.check()
        return manager.list().map { it.path }
    }

    @Synchronized override fun generate(request: ChatRequest, cancellation: Cancellation, onEvent: (GenerationEvent) -> Unit): String {
        cancellation.check()
        require(request.messages.none { it.attachments.isNotEmpty() }) {
            "This local runtime supports text and JSON. Image/PDF attachments require a vision backend; no image was sent."
        }
        require(request.profile.contextSize in 128..16384) { "Local context size must be between 128 and 16384 tokens." }
        require(request.profile.maxTokens in 1 until request.profile.contextSize) { "Maximum output tokens must be smaller than the context size." }
        require(request.profile.temperature.isFinite() && request.profile.temperature in 0.0..2.0) { "Temperature must be between 0 and 2." }
        require(request.messages.all { it.role in setOf("system", "user", "assistant") }) { "This model accepts system, user, and assistant messages." }
        val file = manager.resolve(request.account.model)
        if (file.path != loadedPath) unload()
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val estimate = (if (loadedPath.isEmpty()) file.length() else 0) + 256L * 1024 * request.profile.contextSize + 256L * 1024 * 1024
        require(!memory.lowMemory && memory.availMem > estimate) { "Not enough available memory. Unload other models or reduce the local context size." }
        if (handle == 0L) handle = NativeRuntime.create()
        val active = handle
        val turn = turns.incrementAndGet()
        NativeRuntime.begin(active, turn)
        cancellation.onCancel { NativeRuntime.cancel(active, turn) }
        try {
            if (loadedPath.isEmpty()) {
                onEvent(GenerationEvent(status = "Loading local model"))
                NativeRuntime.load(active, file.path)
                loadedPath = file.path
            }
            cancellation.check()
            val messages = buildList {
                if (request.profile.systemPrompt.isNotBlank()) add(ChatMessage("system", request.profile.systemPrompt))
                addAll(request.messages)
            }
            val result = StringBuilder()
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            var pending = ByteArray(0)
            val callback = object : NativeTokenCallback {
                override fun token(bytes: ByteArray) {
                    cancellation.check()
                    val input = ByteBuffer.wrap(pending + bytes)
                    val output = CharBuffer.allocate(input.remaining() + 2)
                    decoder.decode(input, output, false)
                    pending = ByteArray(input.remaining()).also { input.get(it) }
                    output.flip()
                    val text = output.toString()
                    result.append(text)
                    if (request.profile.streaming && text.isNotEmpty()) onEvent(GenerationEvent(text = text))
                }
            }
            onEvent(GenerationEvent(status = "Generating offline"))
            NativeRuntime.generate(active, messages.map { it.role }.toTypedArray(), messages.map { it.content }.toTypedArray(),
                request.profile.contextSize, request.profile.maxTokens, request.profile.temperature.toFloat(), request.jsonOutput, callback)
            cancellation.check()
            if (pending.isNotEmpty()) {
                val text = pending.toString(Charsets.UTF_8)
                result.append(text)
                if (request.profile.streaming) onEvent(GenerationEvent(text = text))
            }
            if (!request.profile.streaming) onEvent(GenerationEvent(text = result.toString()))
            return result.toString()
        } catch (error: Exception) {
            if (cancellation.cancelled) throw InterruptedIOException("Stopped")
            throw error
        }
    }

    @Synchronized fun unload() {
        if (handle != 0L) NativeRuntime.destroy(handle)
        handle = 0
        loadedPath = ""
    }
    override fun close() = unload()
    companion object { private val turns = AtomicLong() }
}

internal interface NativeTokenCallback { fun token(bytes: ByteArray) }

internal object NativeRuntime {
    init { System.loadLibrary("blindpilot_llama") }
    external fun create(): Long
    external fun begin(handle: Long, turn: Long)
    external fun cancel(handle: Long, turn: Long)
    external fun load(handle: Long, path: String)
    external fun generate(handle: Long, roles: Array<String>, content: Array<String>, contextSize: Int, maxTokens: Int,
                          temperature: Float, json: Boolean, callback: NativeTokenCallback)
    external fun destroy(handle: Long)
}
