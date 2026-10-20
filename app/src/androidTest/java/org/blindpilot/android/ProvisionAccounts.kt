package org.blindpilot.android

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.blindpilot.android.backends.CloudBackend
import org.blindpilot.android.backends.ProviderCatalog
import org.blindpilot.android.core.Cancellation
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Developer provisioning: reads {"provider":"key"} from the app files directory, saves, verifies, deletes the file. */
@RunWith(AndroidJUnit4::class)
class ProvisionAccounts {
    @Test fun provision() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(instrumentation.targetContext.filesDir, "bp-accounts.json")
        assumeTrue("no provisioning file", source.isFile)
        val controller = ChatController.get(instrumentation.targetContext)
        val keys = try { JSONObject(source.readText()) } finally { source.delete() }
        for (provider in keys.keys()) {
            val existing = controller.store.accounts().find { it.provider == provider }
            val account = (existing ?: ProviderCatalog.defaults(provider)).copy(apiKey = keys.getString(provider))
            controller.store.saveAccount(account)
            if (controller.preferences.getString("account", "").isNullOrEmpty()) controller.preferences.edit().putString("account", account.id).commit()
            val result = runCatching { CloudBackend().models(account, Cancellation()).size }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "$provider: saved; " + result.fold({ "$it models listed" }, { "model list failed: ${ChatController.safeError(it)}" }) + "\n")
            })
        }
    }
}
