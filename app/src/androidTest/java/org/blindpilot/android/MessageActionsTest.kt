package org.blindpilot.android

import android.os.Bundle
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.blindpilot.android.core.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the custom actions screen readers receive on real message rows. */
@RunWith(AndroidJUnit4::class)
class MessageActionsTest {
    @Test fun messageRowsExposeActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val controller = ChatController.get(instrumentation.targetContext)
        val previous = controller.preferences.getString("conversation", "")
        val id = controller.store.newConversation("Actions test", "", "")
        try {
            controller.store.addMessage(id, ChatMessage("user", "Question"))
            controller.store.addMessage(id, ChatMessage("assistant", "Answer with https://example.com\n```\nprint(1)\n```"))
            controller.preferences.edit().putString("conversation", id).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                val labels = mutableListOf<List<String>>()
                scenario.onActivity { activity ->
                    fun find(view: android.view.View): ListView? = view as? ListView ?: (view as? android.view.ViewGroup)?.let { group ->
                        (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
                    }
                    val list = find(activity.window.decorView)!!
                    for (i in 0 until list.childCount) {
                        val info = list.getChildAt(i).createAccessibilityNodeInfo()
                        labels += info.actionList.mapNotNull { it.label?.toString() }
                    }
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$labels\n") })
                assertEquals(listOf("Read all", "Copy", "Edit and resend", "Edit", "Resend", "Save as prompt", "New chat from here", "Share", "Delete"), labels[0])
                assertEquals(listOf("Read all", "Copy", "Regenerate", "Edit", "New chat from here", "Share", "Links", "Copy code block", "Delete"), labels[1])
            }
        } finally {
            controller.store.deleteConversation(id)
            controller.preferences.edit().putString("conversation", previous).commit()
        }
    }
}
