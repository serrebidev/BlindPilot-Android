package org.blindpilot.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.blindpilot.android.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoreTest {
    private fun store(): Store {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("blindpilot.db")
        return Store(context, MemorySecrets())
    }
    @Test fun accountSecretsAreSeparateAndRoundTrip() {
        val store = store()
        val account = Account(name="Test", apiKey="private-key", credential="private-refresh")
        store.saveAccount(account)
        assertEquals(account, store.accounts().single())
        val cursor = store.readableDatabase.rawQuery("SELECT payload FROM accounts", null)
        cursor.use { it.moveToFirst(); assertFalse(it.getString(0).contains("private-key")); assertFalse(it.getString(0).contains("private-refresh")) }
        store.close()
    }
    @Test fun interruptedGenerationPreservesHistoryAndDraft() {
        val store = store()
        val id = store.newConversation("Test", "account", "profile")
        store.setDraft(id, "unsent")
        store.addMessage(id, ChatMessage("user", "hello"))
        val message = store.addMessage(id, ChatMessage("assistant", "partial"), "streaming")
        store.recoverInterrupted()
        assertEquals("partial", store.messages(id).last().content)
        assertEquals("interrupted", store.messageState(message))
        assertEquals("unsent", store.conversation(id)!!.draft)
        store.close()
    }
    @Test fun deleteConversationDoesNotDeleteAnotherConversation() {
        val store = store()
        val first = store.newConversation("First", "a", "p")
        val second = store.newConversation("Second", "a", "p")
        store.addMessage(first, ChatMessage("user", "one"))
        store.addMessage(second, ChatMessage("user", "two"))
        store.deleteConversation(first)
        assertEquals(listOf("two"), store.messages(second).map { it.content })
        assertTrue(store.messages(first).isEmpty())
        store.close()
    }
}

private class MemorySecrets : Secrets {
    private val data = mutableMapOf<String,String>()
    override fun put(id:String,value:String) { data[id]=value }
    override fun get(id:String):String = data[id].orEmpty()
    override fun remove(id:String) { data.remove(id) }
}
