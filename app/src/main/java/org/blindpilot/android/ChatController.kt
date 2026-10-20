package org.blindpilot.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.blindpilot.android.actions.ActionLoop
import org.blindpilot.android.actions.DeviceAccessibilityService
import org.blindpilot.android.actions.TaskService
import org.blindpilot.android.backends.CloudBackend
import org.blindpilot.android.backends.HermesBackend
import org.blindpilot.android.backends.ChatGptAuth
import org.blindpilot.android.core.*
import org.blindpilot.android.data.Store
import org.blindpilot.android.local.LocalBackend
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChatController private constructor(private val context:Context) {
    val store = Store(context)
    val preferences = context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    val executor = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var cancellation:Cancellation? = null
    private val local by lazy { LocalBackend(context) }
    // One instance keeps model metadata from the last model list for capability checks.
    private val cloud by lazy { CloudBackend(chatGptToken={ a,c -> auth.accessToken(a,c) }) }
    @Volatile var busy = false; private set
    @Volatile var status = "Ready"; private set
    @Volatile var revision = 0; private set
    @Volatile var activeConversation = ""; private set
    @Volatile var pending:PendingQuestion? = null; private set
    val auth by lazy { ChatGptAuth(context,onCredentials={ account -> store.saveAccount(account) }) }
    init {
        store.recoverInterrupted()
        if(store.profiles().isEmpty()) store.saveProfile(Profile())
    }
    fun listen(listener:()->Unit) { listeners.add(listener) }
    fun unlisten(listener:()->Unit) { listeners.remove(listener) }
    private fun changed() { main.post { listeners.forEach { it() } } }
    fun stop() { cancellation?.cancel(); pending?.answer(null); status="Stopping"; changed() }
    fun backend(account:Account):ChatBackend = when(account.provider) {
        "local" -> local
        "hermes" -> HermesBackend(onQuestion={ q ->
            ask(q.text,q.choices,q.secret,cancellation ?: Cancellation(),q.multiSelect)
                ?: if(q.kind.contains("approval")) "deny" else throw java.io.InterruptedIOException("Question cancelled")
        },onSession={ id ->
            val latest=store.accounts().find { it.id==account.id } ?: account
            store.saveAccount(latest.copy(sessionId=id))
        })
        "samsung" -> throw IllegalStateException("Samsung SDK access is pending. This backend will be available after the authorized SDK is integrated.")
        else -> cloud
    }
    /** [saveUser] is false when answering a user message already stored, as in regenerate or resend. */
    fun send(conversationId:String,request:ChatRequest,deviceActions:Boolean,saveUser:Boolean=true) {
        check(!busy) { "A task is already running" }
        val cancel=Cancellation(); cancellation=cancel;busy=true;activeConversation=conversationId;status="Working";changed()
        if(saveUser) store.addMessage(conversationId,request.messages.last())
        val responseId=store.addMessage(conversationId,ChatMessage("assistant",""),"streaming")
        executor.execute {
            val text=StringBuilder();var savedAt=0L
            try {
                TaskService.start(context,cancel)
                val backend=backend(request.account)
                val events:(GenerationEvent)->Unit={ event ->
                    if(event.text.isNotEmpty()) text.append(event.text)
                    if(event.status.isNotEmpty()) { status=event.status;changed() }
                    if(System.currentTimeMillis()-savedAt>750) { store.updateMessage(responseId,text.toString(),"streaming");savedAt=System.currentTimeMillis();changed() }
                }
                val result=if(deviceActions) {
                    val service=DeviceAccessibilityService.current ?: error("Enable BlindPilot device assistance in Android Accessibility settings first.")
                    ActionLoop(service,backend,{ description -> ask(description,listOf("Allow","Deny"),false,cancel)=="Allow" }).run(request,cancel,events)
                } else backend.generate(request,cancel,events)
                store.updateMessage(responseId,result,"complete")
                status="Response complete"
            } catch(e:Exception) {
                val stopped=cancel.cancelled
                val message=if(stopped) "Stopped. Completed actions are not undone." else "Error: ${safeError(e)}"
                store.updateMessage(responseId,if(text.isEmpty()) message else "$text\n\n$message",if(stopped) "interrupted" else "error")
                status=if(stopped) "Stopped" else message
            } finally {
                TaskService.stop(context);busy=false;cancellation=null;revision++;changed()
            }
        }
    }
    fun ask(text:String,choices:List<String>,secret:Boolean,cancel:Cancellation,multiSelect:Boolean=false):String? {
        cancel.check()
        val question=PendingQuestion(text,choices,secret,multiSelect)
        pending=question;status="Your answer is needed";changed()
        main.post {
            val intent=Intent(context,QuestionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            try { (DeviceAccessibilityService.current ?: context).startActivity(intent) } catch(_:Exception) { status="Open BlindPilot to answer the pending question";changed() }
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10)
            while(!question.latch.await(200,TimeUnit.MILLISECONDS)) { cancel.check();check(System.nanoTime()<deadline) { "Question timed out. No action was approved." } }
            cancel.check()
            return question.result
        } finally { pending=null;changed() }
    }
    fun beginOperation(label:String,operation:(Cancellation)->String) {
        check(!busy) { "A task is already running" }
        val cancel=Cancellation();cancellation=cancel;busy=true;status=label;changed()
        executor.execute { try { status=operation(cancel) } catch(e:Exception) { status=if(cancel.cancelled) "Stopped" else "Error: ${safeError(e)}" } finally { busy=false;cancellation=null;revision++;changed() } }
    }
    fun updateStatus(value:String) { status=value;changed() }
    fun unloadLocal() = local.unload()
    companion object {
        // Holds only the application context.
        @SuppressLint("StaticFieldLeak") @Volatile private var instance:ChatController?=null
        fun get(context:Context):ChatController = instance ?: synchronized(this) { instance ?: ChatController(context.applicationContext).also { instance=it } }
        fun safeError(error:Throwable):String {
            val raw=error.message ?: error.javaClass.simpleName
            return raw.replace(Regex("(?i)(bearer\\s+|token=|ticket=|api[_-]?key=)[^\\s&]+"),"$1[redacted]").take(800)
        }
    }
}

class PendingQuestion(val text:String,val choices:List<String>,val secret:Boolean,val multiSelect:Boolean) {
    val latch=CountDownLatch(1)
    @Volatile var result:String?=null;private set
    @Synchronized fun answer(value:String?) { if(latch.count==0L)return;result=value;latch.countDown() }
}
