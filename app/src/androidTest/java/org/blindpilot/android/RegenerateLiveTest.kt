package org.blindpilot.android

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.blindpilot.android.core.ChatMessage
import org.blindpilot.android.core.ChatRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live provider check of the regenerate path. Costs a request; run with -e live true. */
@RunWith(AndroidJUnit4::class)
class RegenerateLiveTest {
    @Test fun answersStoredMessageWithoutDuplicatingIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("live run not requested", InstrumentationRegistry.getArguments().getString("live") == "true")
        val controller = ChatController.get(instrumentation.targetContext)
        val account = controller.store.accounts().find { it.id == controller.preferences.getString("account", "") }!!
        val profile = controller.store.profiles().find { it.id == controller.preferences.getString("profile", "") }!!
        val id = controller.store.newConversation("Regenerate test", account.id, profile.id)
        try {
            val question = ChatMessage("user", "Reply with only the word pong.")
            controller.store.addMessage(id, question)
            controller.store.addMessage(id, ChatMessage("assistant", "stale answer"))
            controller.store.deleteMessagesAfter(id, controller.store.rows(id).first().id)
            instrumentation.runOnMainSync { controller.send(id, ChatRequest(account, profile.copy(maxTokens = 64), listOf(question)), deviceActions = false, saveUser = false) }
            val deadline = System.currentTimeMillis() + 90_000
            while (controller.busy && System.currentTimeMillis() < deadline) Thread.sleep(250)
            val rows = controller.store.rows(id)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "${account.name} ${account.model}: ${rows.map { "${it.message.role}/${it.state}: ${it.message.content.take(80)}" }}\n") })
            assertEquals(listOf("user", "assistant"), rows.map { it.message.role })
            assertEquals("complete", rows[1].state)
            assertTrue(rows[1].message.content.isNotBlank())
        } finally { controller.store.deleteConversation(id) }
    }
}
