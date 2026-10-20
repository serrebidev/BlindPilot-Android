package org.blindpilot.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.blindpilot.android.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Secrets { fun put(id:String,value:String); fun get(id:String):String; fun remove(id:String) }

class KeystoreSecrets(context: Context) : Secrets {
    private val preferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("blindpilot", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("blindpilot", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun put(id:String,value:String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        check(preferences.edit().putString(id,encoded).commit()) { "Could not save credentials" }
    }
    @Synchronized override fun get(id:String):String {
        val encoded = preferences.getString(id,null) ?: return ""
        val bytes = Base64.decode(encoded,Base64.NO_WRAP)
        require(bytes.size >= 28) { "Stored credential is damaged. Sign in again." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }
    @Synchronized override fun remove(id:String) { check(preferences.edit().remove(id).commit()) }
}

data class MessageRow(val id:Long,val message:ChatMessage,val state:String)
data class Conversation(val id:String,val title:String,val accountId:String,val profileId:String,val updated:Long,val draft:String)

class Store(context:Context, private val secrets:Secrets = KeystoreSecrets(context)) : SQLiteOpenHelper(context,"blindpilot.db",null,1) {
    private val files = context.filesDir
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE profiles(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,account_id TEXT NOT NULL,profile_id TEXT NOT NULL,updated INTEGER NOT NULL,draft TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,conversation_id TEXT NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,attachments TEXT NOT NULL,state TEXT NOT NULL)")
        db.execSQL("CREATE INDEX messages_conversation ON messages(conversation_id,id)")
        db.execSQL("CREATE TABLE prompts(id TEXT PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) { error("Unsupported database upgrade") }
    fun recoverInterrupted() { writableDatabase.execSQL("UPDATE messages SET state='interrupted' WHERE state='streaming'") }
    fun saveAccount(account:Account) {
        val json = JSONObject().put("name",account.name).put("provider",account.provider).put("baseUrl",account.baseUrl)
            .put("apiMode",account.apiMode).put("model",account.model).put("username",account.username)
            .put("sessionId",account.sessionId).put("customHeaders",account.customHeaders).put("customBody",account.customBody)
        secrets.put(account.id,JSONObject().put("apiKey",account.apiKey).put("credential",account.credential).toString())
        writableDatabase.insertWithOnConflict("accounts",null,ContentValues().apply { put("id",account.id); put("payload",json.toString()) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun accounts():List<Account> = readableDatabase.rawQuery("SELECT id,payload FROM accounts ORDER BY rowid",null).use { c ->
        buildList { while(c.moveToNext()) {
            val id=c.getString(0); val j=JSONObject(c.getString(1)); val secret=JSONObject(secrets.get(id).ifEmpty { "{}" })
            add(Account(id=id,name=j.getString("name"),provider=j.getString("provider"),baseUrl=j.optString("baseUrl"),apiKey=secret.optString("apiKey"),credential=secret.optString("credential","token"),apiMode=j.optString("apiMode","chat_completions"),model=j.optString("model"),username=j.optString("username"),sessionId=j.optString("sessionId"),customHeaders=j.optString("customHeaders","{}"),customBody=j.optString("customBody","{}")))
        } }
    }
    fun deleteAccount(id:String) { writableDatabase.delete("accounts","id=?",arrayOf(id)); secrets.remove(id) }
    fun saveProfile(p:Profile) {
        val j=JSONObject().put("name",p.name).put("systemPrompt",p.systemPrompt).put("temperature",p.temperature).put("maxTokens",p.maxTokens).put("contextSize",p.contextSize).put("reasoningEffort",p.reasoningEffort).put("serverTools",JSONArray(p.serverTools)).put("streaming",p.streaming).put("accountId",p.accountId).put("model",p.model)
        writableDatabase.insertWithOnConflict("profiles",null,ContentValues().apply { put("id",p.id); put("payload",j.toString()) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun profiles():List<Profile> = readableDatabase.rawQuery("SELECT id,payload FROM profiles ORDER BY rowid",null).use { c -> buildList {
        while(c.moveToNext()) { val j=JSONObject(c.getString(1)); val tools=j.optJSONArray("serverTools")?:JSONArray(); add(Profile(id=c.getString(0),name=j.getString("name"),systemPrompt=j.optString("systemPrompt"),temperature=j.optDouble("temperature",0.7),maxTokens=j.optInt("maxTokens",2048),contextSize=j.optInt("contextSize",4096),reasoningEffort=j.optString("reasoningEffort"),serverTools=List(tools.length()) { tools.getString(it) },streaming=j.optBoolean("streaming",true),accountId=j.optString("accountId"),model=j.optString("model"))) }
    } }
    fun deleteProfile(id:String) { writableDatabase.delete("profiles","id=?",arrayOf(id)) }
    fun newConversation(title:String,accountId:String,profileId:String):String {
        val id=UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("conversations",null,ContentValues().apply { put("id",id);put("title",title);put("account_id",accountId);put("profile_id",profileId);put("updated",System.currentTimeMillis()) })
        return id
    }
    fun conversations(query:String=""):List<Conversation> = readableDatabase.rawQuery("SELECT id,title,account_id,profile_id,updated,draft FROM conversations WHERE title LIKE ? ESCAPE '\\' OR id IN (SELECT conversation_id FROM messages WHERE content LIKE ? ESCAPE '\\') ORDER BY updated DESC",arrayOf("%${escapeLike(query)}%","%${escapeLike(query)}%")).use { c -> buildList { while(c.moveToNext()) add(Conversation(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getString(5))) } }
    fun conversation(id:String):Conversation? = conversations().firstOrNull { it.id==id }
    fun setDraft(id:String,draft:String) { writableDatabase.update("conversations",ContentValues().apply { put("draft",draft) },"id=?",arrayOf(id)) }
    fun rename(id:String,title:String) { writableDatabase.update("conversations",ContentValues().apply { put("title",title);put("updated",System.currentTimeMillis()) },"id=?",arrayOf(id)) }
    fun setConversationAccount(id:String,accountId:String,profileId:String) { writableDatabase.update("conversations",ContentValues().apply { put("account_id",accountId);put("profile_id",profileId) },"id=?",arrayOf(id)) }
    fun addMessage(conversationId:String,message:ChatMessage,state:String="complete"):Long {
        val id=writableDatabase.insertOrThrow("messages",null,ContentValues().apply { put("conversation_id",conversationId);put("role",message.role);put("content",message.content);put("attachments",attachmentsJson(message.attachments).toString());put("state",state) })
        writableDatabase.update("conversations",ContentValues().apply { put("updated",System.currentTimeMillis()) },"id=?",arrayOf(conversationId))
        return id
    }
    fun updateMessage(id:Long,content:String,state:String) { writableDatabase.update("messages",ContentValues().apply { put("content",content);put("state",state) },"id=?",arrayOf(id.toString())) }
    fun messageState(id:Long):String = readableDatabase.rawQuery("SELECT state FROM messages WHERE id=?",arrayOf(id.toString())).use { if(it.moveToFirst()) it.getString(0) else "" }
    fun messages(conversationId:String):List<ChatMessage> = readableDatabase.rawQuery("SELECT role,content,attachments FROM messages WHERE conversation_id=? ORDER BY id",arrayOf(conversationId)).use { c -> buildList { while(c.moveToNext()) add(ChatMessage(c.getString(0),c.getString(1),parseAttachments(c.getString(2)))) } }
    fun rows(conversationId:String):List<MessageRow> = readableDatabase.rawQuery("SELECT id,role,content,attachments,state FROM messages WHERE conversation_id=? ORDER BY id",arrayOf(conversationId)).use { c -> buildList { while(c.moveToNext()) add(MessageRow(c.getLong(0),ChatMessage(c.getString(1),c.getString(2),parseAttachments(c.getString(3))),c.getString(4))) } }
    // ponytail: attachment files of removed messages stay on disk; sweep unreferenced files if storage matters.
    fun deleteMessage(id:Long) { writableDatabase.delete("messages","id=?",arrayOf(id.toString())) }
    fun deleteMessagesAfter(conversationId:String,id:Long) { writableDatabase.delete("messages","conversation_id=? AND id>?",arrayOf(conversationId,id.toString())) }
    fun deleteConversation(id:String) {
        val db=writableDatabase; db.beginTransaction()
        try { db.delete("messages","conversation_id=?",arrayOf(id)); db.delete("conversations","id=?",arrayOf(id)); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun savePrompt(title:String,body:String) { writableDatabase.insertOrThrow("prompts",null,ContentValues().apply { put("id",UUID.randomUUID().toString());put("title",title);put("body",body) }) }
    fun prompts():List<Pair<String,String>> = readableDatabase.rawQuery("SELECT title,body FROM prompts ORDER BY title",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    fun deletePrompt(title:String) { writableDatabase.delete("prompts","title=?",arrayOf(title)) }
    private fun escapeLike(s:String)=s.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")
    private fun attachmentsJson(list:List<Attachment>)=JSONArray().apply { list.forEach { put(JSONObject().put("name",it.name).put("mimeType",it.mimeType).put("path",it.path)) } }
    private fun parseAttachments(s:String):List<Attachment> { val a=JSONArray(s);return List(a.length()) { val j=a.getJSONObject(it); Attachment(j.getString("name"),j.getString("mimeType"),File(j.getString("path")).canonicalPath.also { path -> require(path.startsWith(files.canonicalPath+File.separator)) { "Invalid stored attachment" } }) } }
}
