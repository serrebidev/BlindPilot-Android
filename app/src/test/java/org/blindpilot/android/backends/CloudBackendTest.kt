package org.blindpilot.android.backends

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.blindpilot.android.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CloudBackendTest {
    private lateinit var server: MockWebServer
    private val backend = CloudBackend()
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }
    private fun account(mode: String = "chat_completions", provider: String = "openai_compatible") =
        Account(provider = provider, baseUrl = server.url("/v1").toString(), apiKey = "secret", apiMode = mode, model = "test")
    private fun request(account: Account = account(), profile: Profile = Profile()) =
        ChatRequest(account, profile, listOf(ChatMessage("user", "Hello")))
    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private fun event(json: String) = "data: $json\n\n"

    @Test fun chatStreamPreservesReasoningAndSettings() {
        server.enqueue(sse(": ping\n\n" + event("""{"choices":[{"delta":{"reasoning_content":"Think","content":"Hi"}}]}""") + event("""{"choices":[{"delta":{"content":"!"},"finish_reason":"stop"}]}""") + event("[DONE]")))
        val events = mutableListOf<GenerationEvent>()
        assertEquals("Hi!", backend.generate(request(), Cancellation(), events::add))
        assertEquals("Think", events.joinToString("") { it.reasoning })
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer secret", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals(2048, body.getInt("max_tokens"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test fun responsesStreamRequiresCompletion() {
        server.enqueue(sse(event("""{"type":"response.output_text.delta","delta":"Answer"}""") + event("""{"type":"response.completed","response":{"status":"completed"}}""")))
        assertEquals("Answer", backend.generate(request(account("responses")), Cancellation()) {})
        assertEquals("/v1/responses", server.takeRequest().path)
        server.enqueue(sse(event("""{"type":"response.output_text.delta","delta":"Partial"}""")))
        val failure = assertThrows(IOException::class.java) { backend.generate(request(account("responses")), Cancellation()) {} }
        assertTrue(failure.message!!.contains("before"))
    }

    @Test fun messagesStreamAndSystemShape() {
        server.enqueue(sse(event("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"Claude"}}""") + event("""{"type":"message_stop"}""")))
        assertEquals("Claude", backend.generate(request(account("messages")), Cancellation()) {})
        val recorded = server.takeRequest()
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = JSONObject(recorded.body.readUtf8())
        assertTrue(body.getString("system").contains("blind"))
        assertEquals(1, body.getJSONArray("messages").length())
    }

    @Test fun structuredErrorsInHttpAndStreamAreReported() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Quota exceeded"}}"""))
        val http = assertThrows(IOException::class.java) { backend.generate(request(), Cancellation()) {} }
        assertTrue(http.message!!.contains("429")); assertTrue(http.message!!.contains("Quota exceeded"))
        server.enqueue(sse(event("""{"type":"error","error":{"message":"Overloaded"}}""")))
        assertTrue(assertThrows(IOException::class.java) { backend.generate(request(account("messages")), Cancellation()) {} }.message!!.contains("Overloaded"))
    }

    @Test fun cancellationClosesBlockedNetworkRead() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {}\n\n").setBodyDelay(4, TimeUnit.SECONDS))
        val cancellation = Cancellation()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit<Boolean> { try { backend.generate(request(), cancellation) {}; false } catch (_: IOException) { true } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            cancellation.cancel()
            // The body is delayed longer than this wait; only cancellation can end the read in time.
            assertTrue(task.get(2, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }

    @Test fun modelListIsDistinctSortedAndAuthenticated() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"z"},{"id":"A"},{"id":"z"}]}"""))
        assertEquals(listOf("A", "z"), backend.models(account(), Cancellation()))
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test fun textAttachmentIsInlinedAndUnsupportedImagesFailBeforeSend() {
        val file = File.createTempFile("blindpilot", ".txt").apply { writeText("Attached text") }
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}]}"""))
            val req = request(profile = Profile(streaming = false)).copy(messages = listOf(ChatMessage("user", "Read", listOf(Attachment("note.txt", "text/plain", file.path)))))
            assertEquals("OK", backend.generate(req, Cancellation()) {})
            assertTrue(server.takeRequest().body.readUtf8().contains("Attached text"))
            assertThrows(IllegalArgumentException::class.java) { backend.generate(req.copy(messages = listOf(ChatMessage("user", "", listOf(Attachment("photo.png", "image/png", file.path))))), Cancellation()) {} }
            assertEquals(1, server.requestCount)
        } finally { file.delete() }
    }

    @Test fun nonStreamingResponsesAndMessagesAreDecoded() {
        server.enqueue(MockResponse().setBody("""{"output":[{"type":"message","content":[{"type":"output_text","text":"Responses"}]}]}"""))
        assertEquals("Responses", backend.generate(request(account("responses"), Profile(streaming = false)), Cancellation()) {})
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Messages"}]}"""))
        assertEquals("Messages", backend.generate(request(account("messages"), Profile(streaming = false)), Cancellation()) {})
    }
}
