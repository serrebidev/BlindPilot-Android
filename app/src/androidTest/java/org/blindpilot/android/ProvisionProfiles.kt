package org.blindpilot.android

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.blindpilot.android.core.Profile
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Developer provisioning: imports desktop profiles from bp-profiles.json in the app files directory, then deletes it. */
@RunWith(AndroidJUnit4::class)
class ProvisionProfiles {
    @Test fun provision() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(instrumentation.targetContext.filesDir, "bp-profiles.json")
        assumeTrue("no provisioning file", source.isFile)
        val controller = ChatController.get(instrumentation.targetContext)
        val rows = try { JSONArray(source.readText()) } finally { source.delete() }
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val tools = row.getJSONArray("serverTools")
            val account = controller.store.accounts().find { it.provider == row.getString("accountProvider") }
            val existing = controller.store.profiles().find { it.name == row.getString("name") }
            val profile = (existing ?: Profile()).copy(name = row.getString("name"), systemPrompt = row.getString("systemPrompt"),
                temperature = row.getDouble("temperature"), maxTokens = row.getInt("maxTokens"), streaming = row.getBoolean("streaming"),
                reasoningEffort = row.getString("reasoningEffort"), serverTools = List(tools.length()) { tools.getString(it) },
                accountId = account?.id.orEmpty(), model = if (account == null) "" else row.getString("model"))
            controller.store.saveProfile(profile)
            if (row.getBoolean("default")) {
                if (account != null && profile.model.isNotBlank()) controller.store.saveAccount(account.copy(model = profile.model))
                controller.preferences.edit().putString("profile", profile.id).apply { if (account != null) putString("account", account.id) }.commit()
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "${profile.name}: ${if (existing == null) "added" else "updated"}\n") })
        }
    }
}
