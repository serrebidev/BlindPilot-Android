package org.blindpilot.android.actions

import org.blindpilot.android.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InterruptedIOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActionLoopTest {
    private val screen = DeviceSnapshot(1, "example.app", listOf(DeviceNode("1:0", "Send", listOf("click"))))
    private val request = ChatRequest(Account(), Profile(), listOf(ChatMessage("user", "Send a message")))

    @Test fun parserRejectsMalformedAndUnrecognizedArguments() {
        for (json in listOf("not json", "{}", "{\"type\":\"shell\",\"reason\":\"Run\"}",
            "{\"type\":\"click\",\"target\":\"1:0\",\"reason\":\"Send\",\"extra\":true}",
            "{\"type\":\"set_text\",\"target\":\"1:0\",\"reason\":\"Write\",\"text\":42}")) {
            assertThrows(IllegalArgumentException::class.java) { DeviceAction.parse(json, screen) }
        }
    }

    @Test fun parserRejectsStaleAndUnsupportedTargets() {
        for (json in listOf("{\"type\":\"click\",\"target\":\"0:0\",\"reason\":\"Send\"}",
            "{\"type\":\"set_text\",\"target\":\"1:0\",\"text\":\"hello\",\"reason\":\"Write\"}")) {
            assertThrows(IllegalArgumentException::class.java) { DeviceAction.parse(json, screen) }
        }
    }

    @Test fun cancellationDuringApprovalPreventsExecution() {
        val cancellation = Cancellation()
        val device = FakeDevice()
        val loop = ActionLoop(device, backend("{\"type\":\"click\",\"target\":\"1:0\",\"reason\":\"Send the message\"}")) {
            cancellation.cancel(); true
        }
        assertThrows(InterruptedIOException::class.java) { loop.run(request, cancellation) {} }
        assertEquals(0, device.executed)
    }

    @Test fun deniedApprovalPreventsExecution() {
        val device = FakeDevice()
        val result = ActionLoop(device, backend("{\"type\":\"click\",\"target\":\"1:0\",\"reason\":\"Send\"}")) { false }
            .run(request, Cancellation()) {}
        assertTrue(result.contains("declined"))
        assertEquals(0, device.executed)
    }

    @Test fun failedActionIsNeverRetried() {
        val device = FakeDevice()
        device.failure = true
        assertThrows(IllegalStateException::class.java) {
            ActionLoop(device, backend("{\"type\":\"click\",\"target\":\"1:0\",\"reason\":\"Send\"}")) { true }
                .run(request, Cancellation()) {}
        }
        assertEquals(1, device.executed)
    }

    @Test fun everyExecutedActionRequiresApprovalAndLoopHasStepLimit() {
        val device = FakeDevice()
        var approvals = 0
        val result = ActionLoop(device, backend("{\"type\":\"back\",\"reason\":\"Go back\"}")) { approvals++; true }
            .run(request, Cancellation()) {}
        assertEquals(12, device.executed)
        assertEquals(12, approvals)
        assertTrue(result.contains("step limit"))
    }

    private inner class FakeDevice : DeviceExecutor {
        var executed = 0
        var failure = false
        override fun snapshot() = screen
        override fun execute(action: DeviceAction, cancellation: Cancellation): String {
            cancellation.check(); executed++
            check(!failure) { "Action did not complete" }
            return "Action accepted; result must be observed"
        }
    }

    private fun backend(json: String) = object : ChatBackend {
        override fun generate(request: ChatRequest, cancellation: Cancellation, onEvent: (GenerationEvent) -> Unit) = json
        override fun models(account: Account, cancellation: Cancellation) = emptyList<String>()
    }
}
