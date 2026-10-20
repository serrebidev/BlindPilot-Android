package org.blindpilot.android

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.blindpilot.android.core.*
import org.blindpilot.android.local.LocalBackend
import org.blindpilot.android.local.ModelManager
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InterruptedIOException
import kotlin.concurrent.thread

/** Real-hardware check. Run with -e modelUrl <https gguf> -e modelSha <sha256> -e modelName <file.gguf>. */
@RunWith(AndroidJUnit4::class)
class LocalInferenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    private fun report(key: String, value: Any) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$key=$value\n") })
    }

    private fun model(): File {
        val url = args.getString("modelUrl"); val sha = args.getString("modelSha"); val name = args.getString("modelName")
        assumeTrue("model arguments not supplied", url != null && sha != null && name != null)
        val manager = ModelManager(context)
        manager.list().find { it.name == name }?.let { return File(it.path) }
        val started = System.nanoTime()
        val installed = manager.download(url!!, name!!, sha!!)
        report("download_seconds", (System.nanoTime() - started) / 1e9)
        return File(installed.path)
    }

    private fun request(path: String, prompt: String, maxTokens: Int) = ChatRequest(
        Account(provider = "local", model = path),
        Profile(systemPrompt = "You are concise.", temperature = 0.2, maxTokens = maxTokens, contextSize = 2048),
        listOf(ChatMessage("user", prompt)),
    )

    @Test fun generatesOfflineAndStops() {
        val path = model().absolutePath
        val backend = LocalBackend(context)
        try {
            var firstToken = 0L
            var pieces = 0
            val started = System.nanoTime()
            val answer = backend.generate(request(path, "Name three primary colors.", 64), Cancellation()) { event ->
                if (event.text.isNotEmpty()) { if (firstToken == 0L) firstToken = System.nanoTime(); pieces++ }
            }
            val done = System.nanoTime()
            report("answer", answer.replace('\n', ' '))
            report("load_and_first_token_seconds", (firstToken - started) / 1e9)
            report("pieces_per_second", pieces / ((done - firstToken) / 1e9))
            assertTrue("empty answer", answer.isNotBlank())

            val cancellation = Cancellation()
            var stoppedAt = 0L
            val worker = thread {
                try { backend.generate(request(path, "Count slowly from one to five hundred in words.", 1500), cancellation) {} }
                catch (_: InterruptedIOException) { stoppedAt = System.nanoTime() }
            }
            Thread.sleep(1500)
            val cancelledAt = System.nanoTime()
            cancellation.cancel()
            worker.join(10_000)
            assertTrue("generation did not stop", stoppedAt != 0L)
            report("cancel_seconds", (stoppedAt - cancelledAt) / 1e9)
        } finally { backend.unload() }
    }
}
