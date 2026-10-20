package org.blindpilot.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.util.Linkify
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import org.blindpilot.android.actions.DeviceAccessibilityService
import org.blindpilot.android.backends.CloudBackend
import org.blindpilot.android.backends.HermesBackend
import org.blindpilot.android.backends.ProviderCatalog
import org.blindpilot.android.core.*
import org.blindpilot.android.data.Conversation
import org.blindpilot.android.data.MessageRow
import org.blindpilot.android.local.ModelCatalog
import org.blindpilot.android.local.ModelManager
import java.io.File
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var controller: ChatController
    private val store get() = controller.store
    private val prefs get() = controller.preferences
    private val models by lazy { ModelManager(this) }
    private val catalog by lazy { ModelCatalog() }
    private lateinit var heading: TextView
    private lateinit var statusView: TextView
    private lateinit var accountButton: Button
    private lateinit var profileButton: Button
    private lateinit var modelButton: Button
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var attachmentsView: TextView
    private lateinit var composer: EditText
    private lateinit var deviceActions: CheckBox
    private var rows = listOf<MessageRow>()
    private var accounts = listOf<Account>()
    private var seenRevision = -1
    private val listener: () -> Unit = { refresh() }
    private var conversationId: String
        get() = prefs.getString("conversation", "").orEmpty()
        set(value) { prefs.edit().putString("conversation", value).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ChatController.get(this)
        setContentView(layout())
        composer.setText(store.conversation(conversationId)?.draft.orEmpty())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() { super.onResume(); controller.listen(listener); reloadAccounts(); refresh() }
    override fun onPause() {
        controller.unlisten(listener)
        if (conversationId.isNotEmpty()) store.setDraft(conversationId, composer.text.toString())
        super.onPause()
    }

    // Layout

    private fun layout(): View {
        val pad = dp(12)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); fitsSystemWindows = true }
        heading = TextView(this).apply { textSize = 22f; isAccessibilityHeading = true }
        root.addView(heading)
        root.addView(row(button("Conversations") { showConversations() }, button("New chat") { newChat() }, button("Menu") { showMenu() }))
        accountButton = button("Account") { chooseAccount() }
        profileButton = button("Profile") { chooseProfile() }
        modelButton = button("Model") { chooseModel() }
        root.addView(row(accountButton, profileButton, modelButton))
        adapter = actionAdapter(mutableListOf(), ::messageActions)
        val empty = TextView(this).apply { text = "No messages yet. Write a message below."; setPadding(0, pad, 0, pad) }
        val list = ListView(this).apply {
            contentDescription = "Messages"
            adapter = this@MainActivity.adapter
            emptyView = empty
            setOnItemClickListener { _, _, position, _ -> openMessage(position) }
            setOnItemLongClickListener { _, _, position, _ -> actionMenu(messageActions(position)); true }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(empty)
        statusView = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; setPadding(0, dp(4), 0, dp(4)) }
        root.addView(statusView)
        attachmentsView = TextView(this)
        root.addView(attachmentsView)
        root.addView(row(button("Attach file") { pickAttachment() }, button("Remove attachments") { pending.clear(); refresh() }, button("Prompts") { showPrompts() }))
        composer = EditText(this).apply {
            hint = "Message"; minLines = 2; maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(labeled("Message", composer))
        deviceActions = CheckBox(this).apply { text = "Let the model operate this device" }
        root.addView(deviceActions)
        root.addView(row(button("Send") { send() }, button("Stop") { stop() }))
        return root
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }
    private fun labeled(label: String, field: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (field.id == View.NO_ID) field.id = View.generateViewId()
        addView(TextView(context).apply { text = label; labelFor = field.id })
        addView(field)
    }
    private fun input(value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply { setText(value); inputType = type }
    private fun spinner(options: List<String>, selected: String) = Spinner(this).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
        setSelection(options.indexOf(selected).coerceAtLeast(0))
    }

    // State

    private fun reloadAccounts() { accounts = store.accounts() }
    private fun conversation() = store.conversation(conversationId)
    private fun currentAccount(): Account? {
        val id = conversation()?.accountId ?: prefs.getString("account", "")
        return accounts.find { it.id == id } ?: accounts.firstOrNull()
    }
    private fun currentProfile(): Profile {
        val all = store.profiles()
        val id = conversation()?.profileId ?: prefs.getString("profile", "")
        return all.find { it.id == id } ?: all.first()
    }
    private fun say(message: String) = controller.updateStatus(message)
    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }
    private fun task(label: String, operation: (Cancellation) -> String) {
        if (controller.busy) say("Another task is running. Choose Stop or wait for it to finish.")
        else controller.beginOperation(label, operation)
    }

    private fun refresh() {
        if (controller.revision != seenRevision) { seenRevision = controller.revision; reloadAccounts() }
        val conversation = conversation()
        if (conversation == null && conversationId.isNotEmpty()) conversationId = ""
        heading.text = conversation?.title ?: "New conversation"
        val account = currentAccount()
        accountButton.text = "Account: ${account?.name ?: "none"}"
        modelButton.text = "Model: ${account?.let { modelName(it) }?.ifBlank { null } ?: "not chosen"}"
        profileButton.text = "Profile: ${currentProfile().name}"
        val latest = if (conversation == null) emptyList() else store.rows(conversation.id)
        if (latest != rows) { rows = latest; adapter.clear(); adapter.addAll(rows.map(::summary)) }
        if (statusView.text.toString() != controller.status) statusView.text = controller.status
        attachmentsView.text = if (pending.isEmpty()) "No attachments" else "Attachments: " + pending.joinToString { it.name }
    }

    private fun modelName(account: Account) = if (account.provider == "local") File(account.model).name else account.model
    private fun providerLabel(id: String) = when (id) {
        "hermes" -> "Remote Hermes"; "local" -> "On-device model"; "samsung" -> "Samsung Galaxy AI"
        else -> ProviderCatalog.find(id)?.label ?: id
    }
    private fun summary(row: MessageRow): String {
        val message = row.message
        val state = row.state
        val who = when (message.role) { "user" -> "You"; "assistant" -> "Assistant"; else -> message.role }
        val note = when (state) { "streaming" -> " (writing)"; "interrupted" -> " (interrupted)"; "error" -> " (error)"; else -> "" }
        val files = if (message.attachments.isEmpty()) "" else " Attachments: " + message.attachments.joinToString { it.name } + "."
        val text = message.content.ifBlank { "No text yet." }
        val preview = if (text.length <= 400) text else text.take(400) + "… Open to read all ${text.length} characters."
        return "$who$note: $preview$files"
    }

    // Chat

    private fun newChat() {
        if (conversationId.isNotEmpty()) store.setDraft(conversationId, composer.text.toString())
        conversationId = ""
        composer.setText("")
        refresh()
        say("New conversation")
        composer.requestFocus()
    }

    private fun send() {
        if (controller.busy) return say("A task is already running. Choose Stop first.")
        val text = composer.text.toString().trim()
        if (text.isEmpty() && pending.isEmpty()) { composer.requestFocus(); return say("Write a message first.") }
        reloadAccounts()
        val account = currentAccount() ?: return say("Add an account first: open Menu, then Accounts.")
        if (account.model.isBlank() && account.provider != "hermes") return say("Choose a model first with the Model button.")
        val profile = currentProfile()
        if (conversationId.isEmpty()) conversationId = store.newConversation(text.lineSequence().first().take(60).ifBlank { "Attachment" }, account.id, profile.id)
        val history = store.rows(conversationId).filter { it.state == "complete" }.map { it.message }
        val request = ChatRequest(account, profile, history + ChatMessage("user", text, pending.toList()))
        try { controller.send(conversationId, request, deviceActions.isChecked) } catch (e: Exception) { return say(ChatController.safeError(e)) }
        pending.clear()
        composer.setText("")
        store.setDraft(conversationId, "")
        refresh()
    }

    private fun stop() { if (controller.busy) controller.stop() else say("Nothing is running.") }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> { send(); return true }
            KeyEvent.KEYCODE_N -> { newChat(); return true }
            KeyEvent.KEYCODE_L -> { showConversations(); return true }
            KeyEvent.KEYCODE_M -> { showMenu(); return true }
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && controller.busy) { stop(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun openMessage(position: Int) {
        val message = rows.getOrNull(position)?.message ?: return
        val body = TextView(this).apply {
            text = message.content; setTextIsSelectable(true); textSize = 18f; setPadding(dp(16), dp(8), dp(16), dp(8))
            Linkify.addLinks(this, Linkify.WEB_URLS)
        }
        val links = linksIn(message.content)
        val code = codeIn(message.content)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (links.isNotEmpty()) box.addView(button("Links (${links.size})") { pick("Links", links) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(links[it]))) } })
        if (code.isNotEmpty()) box.addView(button("Code blocks (${code.size})") { pick("Code blocks", code.mapIndexed { i, c -> "Block ${i + 1}: " + c.lineSequence().first().take(80) }) { copy(code[it], "Code block") } })
        box.addView(body)
        AlertDialog.Builder(this).setTitle(if (message.role == "user") "Your message" else "Assistant message")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ -> copy(message.content, "Message") }
            .setNegativeButton("Share") { _, _ -> share(message.content) }
            .show()
    }

    private fun copy(text: String, label: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        say("$label copied")
    }

    // Item actions: TalkBack lists these in its actions menu; long press shows the same list.

    private fun actionAdapter(items: MutableList<String>, actions: (Int) -> List<Pair<String, () -> Unit>>) =
        object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).apply {
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        actions(position).take(ACTION_IDS.size).forEachIndexed { i, (label, _) -> info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_IDS[i], label)) }
                    }
                    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                        val chosen = actions(position).getOrNull(ACTION_IDS.indexOf(action)) ?: return super.performAccessibilityAction(host, action, args)
                        chosen.second()
                        return true
                    }
                }
            }
        }

    private fun actionMenu(actions: List<Pair<String, () -> Unit>>) {
        if (actions.isNotEmpty()) pick("Actions", actions.map { it.first }) { actions[it].second() }
    }

    private fun messageActions(position: Int): List<Pair<String, () -> Unit>> {
        val row = rows.getOrNull(position) ?: return emptyList()
        val text = row.message.content
        val actions = mutableListOf<Pair<String, () -> Unit>>("Read all" to { openMessage(position) }, "Copy" to { copy(text, "Message") })
        if (row.state == "streaming") return actions
        if (row.message.role == "assistant") {
            actions += "Regenerate" to { regenerate(row) }
            actions += "Edit" to { editMessage(row, resend = false) }
        } else {
            actions += "Edit and resend" to { editMessage(row, resend = true) }
            actions += "Edit" to { editMessage(row, resend = false) }
            actions += "Resend" to { answer(row) }
            actions += "Save as prompt" to { textPrompt("Prompt title", text.lineSequence().first().take(60)) { store.savePrompt(it, text); say("Prompt saved") } }
        }
        actions += "New chat from here" to { branch(row) }
        actions += "Share" to { share(text) }
        val links = linksIn(text)
        if (links.isNotEmpty()) actions += "Links" to { pick("Links", links) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(links[it]))) } }
        val code = codeIn(text)
        if (code.isNotEmpty()) actions += "Copy code block" to { pick("Code blocks", code.mapIndexed { i, c -> "Block ${i + 1}: " + c.lineSequence().first().take(80) }) { copy(code[it], "Code block") } }
        actions += "Delete" to { confirm("Delete this message?") { store.deleteMessage(row.id); refresh(); say("Message deleted") } }
        return actions
    }

    private fun share(text: String) = startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share message"))
    private fun codeIn(text: String) = Regex("```[^\\n]*\\n([\\s\\S]*?)```").findAll(text).map { it.groupValues[1] }.toList()

    /** Copies history up to and including [row] into a new conversation, leaving the original untouched. */
    private fun branch(row: MessageRow) {
        val source = conversation() ?: return
        val id = store.newConversation("${source.title} (branch)", source.accountId, source.profileId)
        rows.filter { it.id <= row.id }.forEach { store.addMessage(id, it.message, it.state) }
        openConversation(id)
    }

    private fun linksIn(text: String) = Regex("https?://[^\\s)>\\]\"']+").findAll(text).map { it.value }.distinct().toList()

    /** Answers [user] again. Later messages are removed only after confirmation. */
    private fun answer(user: MessageRow) {
        if (controller.busy) return say("A task is already running. Choose Stop first.")
        val later = rows.count { it.id > user.id }
        val run = {
            store.deleteMessagesAfter(conversationId, user.id)
            reloadAccounts()
            val account = currentAccount()
            if (account == null) say("Add an account first: open Menu, then Accounts.")
            else {
                val history = store.rows(conversationId).filter { it.state == "complete" || it.id == user.id }.map { it.message }
                try { controller.send(conversationId, ChatRequest(account, currentProfile(), history), deviceActions = false, saveUser = false) }
                catch (e: Exception) { say(ChatController.safeError(e)) }
                refresh()
            }
        }
        if (later == 0) run() else confirm("This removes the $later later ${if (later == 1) "message" else "messages"} in this conversation and sends again. Continue?") { run() }
    }

    private fun regenerate(response: MessageRow) {
        val user = rows.lastOrNull { it.id < response.id && it.message.role == "user" } ?: return say("There is no message of yours before this response.")
        answer(user)
    }

    private fun editMessage(row: MessageRow, resend: Boolean) {
        if (controller.busy) return say("A task is already running. Choose Stop first.")
        val field = input(row.message.content, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply { minLines = 3 }
        form(if (resend) "Edit and resend" else "Edit message", listOf(labeled("Message text", field))) {
            val text = field.text.toString().trim()
            if (text.isEmpty() && row.message.attachments.isEmpty()) return@form "The message cannot be empty."
            store.updateMessage(row.id, text, "complete")
            refresh()
            if (resend) answer(rows.first { it.id == row.id }) else say("Message edited")
            null
        }
    }

    // Lists and dialogs

    private fun pick(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()) { _, which -> onPick(which) }.setNegativeButton("Cancel", null).show()
    }

    /** A filterable list for long collections such as models and conversations. */
    private fun searchList(title: String, items: List<String>, extra: Pair<String, () -> Unit>? = null, neutral: Pair<String, () -> Unit>? = null,
                           actions: ((Int) -> List<Pair<String, () -> Unit>>)? = null, onPick: (Int) -> Unit) {
        val shown = items.indices.toMutableList()
        var dialog: AlertDialog? = null
        // Item actions close the list first; most open their own dialog or change the list.
        val itemActions = { position: Int -> actions?.invoke(shown[position]).orEmpty().map { (label, run) -> label to { dialog?.dismiss(); run() } } }
        val listAdapter = actionAdapter(items.toMutableList(), itemActions)
        val filter = input("")
        val list = ListView(this).apply { adapter = listAdapter; contentDescription = title }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        box.addView(labeled("Filter", filter))
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        val builder = AlertDialog.Builder(this).setTitle("$title (${items.size})").setView(box).setNegativeButton("Close", null)
        extra?.let { (label, action) -> builder.setPositiveButton(label) { _, _ -> action() } }
        neutral?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action() } }
        dialog = builder.show()
        list.setOnItemClickListener { _, _, position, _ -> dialog?.dismiss(); onPick(shown[position]) }
        if (actions != null) list.setOnItemLongClickListener { _, _, position, _ -> actionMenu(itemActions(position)); true }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                shown.clear(); shown.addAll(items.indices.filter { items[it].contains(query, true) })
                listAdapter.clear(); listAdapter.addAll(shown.map { items[it] })
            }
        })
    }

    /** Keeps the form open and reports the problem when [save] returns an error. */
    private fun form(title: String, fields: List<View>, save: () -> String?) {
        val error = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); addView(error); fields.forEach(::addView) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val problem = try { save() } catch (e: Exception) { ChatController.safeError(e) }
            if (problem == null) { dialog.dismiss(); reloadAccounts(); refresh() } else error.text = problem
        }
    }

    private fun textPrompt(title: String, value: String, save: (String) -> Unit) {
        val field = input(value)
        form(title, listOf(labeled(title, field))) { val text = field.text.toString().trim(); if (text.isEmpty()) "Enter a value." else { save(text); null } }
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Confirm").setMessage(message).setPositiveButton("Yes") { _, _ -> action() }.setNegativeButton("No", null).show()
    }

    // Conversations

    private fun showConversations() {
        val all = store.conversations()
        if (all.isEmpty()) return say("No saved conversations yet.")
        fun actions(chosen: Conversation) = listOf<Pair<String, () -> Unit>>(
            "Open" to { openConversation(chosen.id) },
            "Rename" to { textPrompt("Conversation title", chosen.title) { store.rename(chosen.id, it) } },
            "Export" to { export(chosen.id) },
            "Delete" to { confirm("Delete ${chosen.title}? This cannot be undone.") { store.deleteConversation(chosen.id); refresh(); say("Conversation deleted") } },
        )
        searchList("Conversations", all.map { it.title }, "New chat" to ::newChat, actions = { actions(all[it]) }) { openConversation(all[it].id) }
    }

    private fun openConversation(id: String) {
        if (conversationId.isNotEmpty()) store.setDraft(conversationId, composer.text.toString())
        conversationId = id
        composer.setText(conversation()?.draft.orEmpty())
        refresh()
        say("Opened ${heading.text}")
    }

    private var exportId = ""
    @Suppress("DEPRECATION")
    private fun export(id: String) {
        exportId = id
        val title = store.conversation(id)?.title ?: "conversation"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/markdown")
            .putExtra(Intent.EXTRA_TITLE, title.replace(Regex("[\\\\/:*?\"<>|]"), " ").take(60) + ".md"), REQUEST_EXPORT)
    }

    // Menu

    private fun showMenu() {
        val entries = listOf<Pair<String, () -> Unit>>(
            "Accounts" to ::showAccounts, "Profiles" to ::showProfiles, "On-device models" to ::showLocalModels,
            "Hermes sessions" to ::showHermesSessions, "Hermes commands" to ::showHermesCommands,
            "Rename conversation" to { conversation()?.let { c -> textPrompt("Conversation title", c.title) { store.rename(c.id, it) } } ?: say("Open a conversation first.") },
            "Export conversation" to { if (conversationId.isEmpty()) say("Open a conversation first.") else export(conversationId) },
            "Delete conversation" to { conversation()?.let { c -> confirm("Delete ${c.title}? This cannot be undone.") { store.deleteConversation(c.id); newChat() } } ?: say("Open a conversation first.") },
            "Device assistance" to ::showDeviceAssistance, "Help" to ::showHelp,
        )
        pick("Menu", entries.map { it.first }) { entries[it].second() }
    }

    private fun showPrompts() {
        val prompts = store.prompts()
        val items = prompts.map { it.first } + "Save current message as a prompt"
        pick("Saved prompts", items) { index ->
            if (index == prompts.size) {
                val body = composer.text.toString().trim()
                if (body.isEmpty()) say("Write the prompt in the message field first.") else textPrompt("Prompt title", "") { store.savePrompt(it, body); say("Prompt saved") }
            } else pick(prompts[index].first, listOf("Insert", "Delete")) { action ->
                if (action == 0) { composer.text.insert(composer.selectionStart.coerceAtLeast(0), prompts[index].second); composer.requestFocus() }
                else { store.deletePrompt(prompts[index].first); say("Prompt deleted") }
            }
        }
    }

    // Accounts

    private fun chooseAccount() {
        reloadAccounts()
        if (accounts.isEmpty()) return showAccounts()
        pick("Use account", accounts.map { "${it.name}, ${providerLabel(it.provider)}" }) { index -> useAccount(accounts[index]) }
    }

    private fun useAccount(account: Account) {
        prefs.edit().putString("account", account.id).apply()
        conversation()?.let { store.setConversationAccount(it.id, account.id, it.profileId) }
        refresh()
        say("Using ${account.name}")
    }

    private fun showAccounts() {
        reloadAccounts()
        val items = accounts.map { "${it.name}, ${providerLabel(it.provider)}" } + "Add account"
        pick("Accounts", items) { index ->
            if (index == accounts.size) return@pick addAccount()
            val account = accounts[index]
            val actions = mutableListOf("Use now", "Edit", "Delete")
            if (account.provider == "chatgpt") actions.add(1, "Sign in with ChatGPT")
            pick(account.name, actions) { action ->
                when (actions[action]) {
                    "Use now" -> useAccount(account)
                    "Edit" -> editAccount(account)
                    "Sign in with ChatGPT" -> signInChatGpt(account)
                    "Delete" -> confirm("Delete account ${account.name}? Its saved credentials are removed.") { store.deleteAccount(account.id); reloadAccounts(); refresh() }
                }
            }
        }
    }

    private fun addAccount() {
        val ids = ProviderCatalog.providers.map { it.id } + listOf("hermes", "local", "samsung")
        pick("Account type", ids.map(::providerLabel)) { index ->
            when (val id = ids[index]) {
                "samsung" -> AlertDialog.Builder(this).setTitle("Samsung Galaxy AI").setMessage(SAMSUNG_PENDING).setPositiveButton("OK", null).show()
                "hermes" -> editAccount(Account(name = "Hermes", provider = "hermes"))
                "local" -> editAccount(Account(name = "On-device", provider = "local"))
                else -> editAccount(ProviderCatalog.defaults(id))
            }
        }
    }

    private fun editAccount(account: Account) {
        val isNew = accounts.none { it.id == account.id }
        val name = input(account.name)
        val baseUrl = input(account.baseUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val secret = input(account.apiKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val model = input(account.model)
        val apiMode = spinner(listOf("auto", "chat_completions", "responses", "messages"), account.apiMode)
        val auth = spinner(listOf("token", "password"), account.credential.takeIf { it in setOf("token", "password") } ?: "token")
        val username = input(account.username)
        val headers = input(account.customHeaders)
        val body = input(account.customBody)
        val fields = mutableListOf<View>(labeled("Account name", name))
        when (account.provider) {
            "hermes" -> fields += listOf(labeled("Gateway address, for example wss://host/api/ws", baseUrl), labeled("Authentication", auth),
                labeled("Username, for password authentication", username), labeled("Session token or password", secret), labeled("Model, optional", model))
            "local" -> fields += TextView(this).apply { text = "Choose the model file with the Model button after saving. Install models from Menu, On-device models." }
            "chatgpt" -> fields += TextView(this).apply { text = "After saving, choose Sign in with ChatGPT. An OpenAI API key cannot use a ChatGPT plan." }
            else -> {
                if (account.provider == "openai_compatible") fields += listOf(labeled("Base URL", baseUrl), labeled("API mode", apiMode))
                fields += listOf(labeled("API key", secret), labeled("Model ID, or choose later with the Model button", model),
                    labeled("Custom headers, JSON object", headers), labeled("Custom request fields, JSON object", body))
            }
        }
        form(if (isNew) "Add ${providerLabel(account.provider)} account" else "Edit ${account.name}", fields) {
            if (name.text.isBlank()) return@form "Enter an account name."
            val updated = when (account.provider) {
                "hermes" -> account.copy(baseUrl = HermesBackend.normalizedEndpoint(baseUrl.text.toString()), credential = auth.selectedItem as String,
                    username = username.text.toString().trim(), apiKey = secret.text.toString(), model = model.text.toString().trim())
                "local", "chatgpt" -> account
                else -> account.copy(baseUrl = baseUrl.text.toString().trim(), apiMode = if (account.provider == "openai_compatible") apiMode.selectedItem as String else account.apiMode,
                    apiKey = secret.text.toString().trim(), model = model.text.toString().trim(), customHeaders = headers.text.toString().ifBlank { "{}" }, customBody = body.text.toString().ifBlank { "{}" })
            }.copy(name = name.text.toString().trim())
            if (updated.provider == "hermes" && updated.credential == "password" && updated.username.isBlank()) return@form "Enter the Hermes username."
            store.saveAccount(updated)
            if (isNew) { prefs.edit().putString("account", updated.id).apply(); conversation()?.let { store.setConversationAccount(it.id, updated.id, it.profileId) } }
            if (isNew && updated.provider == "chatgpt") signInChatGpt(updated)
            null
        }
    }

    private fun signInChatGpt(account: Account) = task("Waiting for ChatGPT sign-in in your browser. Choose Stop to cancel.") { cancel ->
        val result = controller.auth.signIn(cancel, { url -> ui { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            account.takeIf { it.credential.trimStart().startsWith("{") })
        store.saveAccount(account.copy(credential = result.credential, name = if (account.name == "ChatGPT plan") result.label else account.name))
        "Signed in to ChatGPT as ${result.label}"
    }

    private fun chooseModel() {
        val account = currentAccount() ?: return say("Add an account first: open Menu, then Accounts.")
        fun save(model: String) { store.saveAccount(account.copy(model = model)); reloadAccounts(); refresh(); say("Model set to ${modelName(account.copy(model = model))}") }
        when (account.provider) {
            "samsung" -> say(SAMSUNG_PENDING)
            "local" -> {
                val installed = models.list()
                if (installed.isEmpty()) return say("No on-device models installed. Open Menu, then On-device models.")
                pick("On-device model", installed.map { "${it.name}, ${size(it.bytes)}" }) { save(installed[it].path) }
            }
            else -> task("Loading models for ${account.name}") { cancel ->
                val backend = controller.backend(account)
                val ids = backend.models(account, cancel)
                val dates = ids.associateWith { (backend as? CloudBackend)?.created(account, it) ?: 0L }
                ui { showModels(ids, dates, account.model, ::save) }
                "${ids.size} models available"
            }
        }
    }

    private fun showModels(ids: List<String>, dates: Map<String, Long>, current: String, save: (String) -> Unit) {
        val order = prefs.getString("model_sort", MODEL_SORTS.first()).orEmpty()
        val sorted = when (order) {
            "Newest first" -> ids.sortedByDescending { dates[it] ?: 0 }
            "Oldest first" -> ids.sortedBy { dates[it]?.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }
            "Z to A" -> ids.sortedWith(String.CASE_INSENSITIVE_ORDER).reversed()
            else -> ids.sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        val undated = order.endsWith("first") && dates.values.none { it > 0 }
        searchList("Models, $order${if (undated) "; this provider gives no dates" else ""}", sorted,
            "Enter model ID" to { textPrompt("Model ID", current) { save(it) } },
            "Sort" to { pick("Sort models", MODEL_SORTS) { prefs.edit().putString("model_sort", MODEL_SORTS[it]).apply(); showModels(ids, dates, current, save) } }) { save(sorted[it]) }
    }

    // Profiles

    private fun chooseProfile() {
        val all = store.profiles()
        pick("Use profile", all.map { it.name }) { index -> useProfile(all[index]) }
    }

    private fun useProfile(profile: Profile) {
        reloadAccounts()
        val account = accounts.find { it.id == profile.accountId }?.let { if (profile.model.isBlank()) it else it.copy(model = profile.model).also(store::saveAccount) }
        prefs.edit().putString("profile", profile.id).apply { if (account != null) putString("account", account.id) }.apply()
        conversation()?.let { store.setConversationAccount(it.id, account?.id ?: it.accountId, profile.id) }
        reloadAccounts()
        refresh()
        say("Using profile ${profile.name}" + (account?.let { ", ${it.name}, ${modelName(it)}" } ?: ""))
    }

    private fun showProfiles() {
        val all = store.profiles()
        pick("Profiles", all.map { it.name } + "Add profile") { index ->
            if (index == all.size) return@pick editProfile(Profile(name = "New profile"))
            pick(all[index].name, listOf("Edit", "Delete")) { action ->
                if (action == 0) editProfile(all[index])
                else if (all.size == 1) say("Keep at least one profile.")
                else confirm("Delete profile ${all[index].name}?") { store.deleteProfile(all[index].id); refresh() }
            }
        }
    }

    private fun editProfile(profile: Profile) {
        val name = input(profile.name)
        val system = input(profile.systemPrompt, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 3 }
        val number = InputType.TYPE_CLASS_NUMBER
        val temperature = input(profile.temperature.toString(), number or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val maxTokens = input(profile.maxTokens.toString(), number)
        val context = input(profile.contextSize.toString(), number)
        val efforts = listOf("", "none", "minimal", "low", "medium", "high", "xhigh", "max")
        val effort = spinner(efforts.map { it.ifEmpty { "Provider default" } }, profile.reasoningEffort.ifEmpty { "Provider default" })
        val streaming = CheckBox(this).apply { text = "Stream responses"; isChecked = profile.streaming }
        val tools = profile.serverTools.toMutableSet()
        val toolIds = ProviderCatalog.serverTools.keys.toList()
        val toolsButton = Button(this).apply { isAllCaps = false }
        fun toolsLabel() { toolsButton.text = "OpenRouter server tools: ${if (tools.isEmpty()) "none" else tools.joinToString { ProviderCatalog.serverTools[it].orEmpty() }}" }
        toolsLabel()
        toolsButton.setOnClickListener {
            val checked = toolIds.map { it in tools }.toBooleanArray()
            AlertDialog.Builder(this).setTitle("Server tools run at OpenRouter, not on this device")
                .setMultiChoiceItems(ProviderCatalog.serverTools.values.toTypedArray(), checked) { _, which, on -> if (on) tools.add(toolIds[which]) else tools.remove(toolIds[which]) }
                .setPositiveButton("Done") { _, _ -> toolsLabel() }.show()
        }
        reloadAccounts()
        val account = spinner(listOf("Keep current account") + accounts.map { it.name }, accounts.find { it.id == profile.accountId }?.name ?: "")
        val model = input(profile.model)
        form("Profile", listOf(labeled("Profile name", name), labeled("Default account", account), labeled("Default model ID, used with the default account", model),
            labeled("System prompt", system), labeled("Temperature, 0 to 2", temperature),
            labeled("Maximum output tokens", maxTokens), labeled("Context size for on-device models", context), labeled("Reasoning effort", effort), streaming, toolsButton)) {
            val t = temperature.text.toString().toDoubleOrNull()?.takeIf { it in 0.0..2.0 } ?: return@form "Temperature must be a number from 0 to 2."
            val m = maxTokens.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: return@form "Maximum output tokens must be a positive number."
            val c = context.text.toString().toIntOrNull()?.takeIf { it in 128..16384 } ?: return@form "Context size must be from 128 to 16384."
            if (name.text.isBlank()) return@form "Enter a profile name."
            store.saveProfile(profile.copy(name = name.text.toString().trim(), systemPrompt = system.text.toString(), temperature = t, maxTokens = m, contextSize = c,
                reasoningEffort = efforts[effort.selectedItemPosition], streaming = streaming.isChecked, serverTools = toolIds.filter { it in tools },
                accountId = accounts.getOrNull(account.selectedItemPosition - 1)?.id.orEmpty(), model = model.text.toString().trim()))
            null
        }
    }

    // On-device models

    private fun showLocalModels() {
        val installed = models.list()
        val items = installed.map { "${it.name}, ${size(it.bytes)}" } + listOf("Browse Hugging Face models", "Import GGUF file", "Download model from address")
        pick("On-device models", items) { index ->
            when (index) {
                installed.size -> browseModels()
                installed.size + 1 -> importModel()
                installed.size + 2 -> downloadModel()
                else -> {
                    val model = installed[index]
                    pick(model.name, listOf("Use in on-device account", "Delete")) { action ->
                        if (action == 0) {
                            reloadAccounts()
                            val account = (accounts.find { it.provider == "local" } ?: Account(name = "On-device", provider = "local")).copy(model = model.path)
                            store.saveAccount(account); useAccount(account)
                        } else confirm("Delete ${model.name}? It is removed from this tablet.") {
                            controller.unloadLocal(); models.delete(model.path); say("${model.name} deleted")
                        }
                    }
                }
            }
        }
    }

    private fun browseModels() {
        val query = input("")
        val sorts = ModelCatalog.SORTS.keys.toList()
        val sort = spinner(sorts, sorts.first())
        form("Browse Hugging Face models", listOf(labeled("Search, for example qwen3 or gemma; blank lists all", query), labeled("Sort by", sort))) {
            val text = query.text.toString()
            val order = ModelCatalog.SORTS.getValue(sort.selectedItem as String)
            task("Searching Hugging Face") { cancel ->
                val results = catalog.search(text, order, cancel)
                ui {
                    searchList("GGUF models", results.map { "${it.id}, ${it.downloads} downloads, ${it.likes} likes${if (it.gated) ", access request required" else ""}" }) { index ->
                        if (results[index].gated) say("${results[index].id} requires an access request on Hugging Face. Choose another model.") else browseFiles(results[index].id)
                    }
                }
                "${results.size} models found"
            }
            null
        }
    }

    private fun browseFiles(modelId: String) = task("Listing files in $modelId") { cancel ->
        val files = catalog.files(modelId, cancel)
        val memory = android.app.ActivityManager.MemoryInfo().also { getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        ui {
            if (files.isEmpty()) say("$modelId has no single-file GGUF models this app can run.")
            else pick(modelId, files.map { "${it.name}, ${size(it.bytes)}${if (it.bytes > memory.totalMem / 2) ", likely too large for this device" else ""}" }) { index ->
                val file = files[index]
                confirm("Download ${file.name}, ${size(file.bytes)}? It is checked against the publisher's SHA-256 before use.") {
                    task("Downloading ${file.name}. Choose Stop to pause; downloading again resumes.") { c ->
                        "${models.download(file.url, file.name, file.sha256, c, progress("Downloading")).name} downloaded and verified"
                    }
                }
            }
        }
        "${files.size} files in $modelId"
    }

    @Suppress("DEPRECATION")
    private fun importModel() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_MODEL)

    private fun downloadModel() {
        val url = input("https://", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val name = input("")
        val hash = input("")
        form("Download model", listOf(labeled("GGUF download address", url), labeled("File name ending in .gguf", name),
            labeled("Publisher SHA-256 checksum, 64 characters", hash))) {
            val values = Triple(url.text.toString().trim(), name.text.toString().trim(), hash.text.toString().trim())
            task("Downloading ${values.second}. Choose Stop to pause; downloading again resumes.") { cancel ->
                val model = models.download(values.first, values.second, values.third, cancel, progress("Downloading"))
                "${model.name} downloaded and verified"
            }
            null
        }
    }

    private fun progress(label: String): (Long, Long) -> Unit {
        var last = -1L
        return { done, total ->
            val step = if (total > 0) done * 10 / total else done / (100L * 1024 * 1024)
            if (step != last) { last = step; say(if (total > 0) "$label, ${step * 10} percent" else "$label, ${size(done)}") }
        }
    }

    private fun size(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${bytes / (1L shl 20)} MB"

    // Hermes

    private fun hermesAccount(): Account? = currentAccount()?.takeIf { it.provider == "hermes" } ?: run { say("Choose a Hermes account first."); null }

    private fun showHermesSessions() {
        val account = hermesAccount() ?: return
        task("Loading Hermes sessions") { cancel ->
            val sessions = HermesBackend().listSessions(account, cancel)
            ui {
                val labels = sessions.map { s -> "${s.title.ifBlank { s.preview.take(60).ifBlank { s.id } }}, ${s.messageCount} messages${if (s.active) ", running" else ""}" }
                searchList("Hermes sessions", labels, "New session" to {
                    store.saveAccount(account.copy(sessionId = "")); reloadAccounts(); newChat(); say("The next message starts a new Hermes session.")
                }) { index -> openHermesSession(account, sessions[index].id, labels[index]) }
            }
            "${sessions.size} Hermes sessions"
        }
    }

    private fun openHermesSession(account: Account, sessionId: String, title: String) {
        val chosen = account.copy(sessionId = sessionId)
        store.saveAccount(chosen)
        task("Loading Hermes history") { cancel ->
            val history = HermesBackend().history(chosen, cancel)
            ui {
                val id = store.newConversation(title.substringBefore(',').take(60), chosen.id, currentProfile().id)
                history.forEach { store.addMessage(id, it) }
                openConversation(id)
            }
            "Hermes session opened with ${history.size} messages"
        }
    }

    private fun showHermesCommands() {
        val account = hermesAccount() ?: return
        task("Loading Hermes commands") { cancel ->
            val commands = HermesBackend().slashCommands(account, cancel)
            ui { searchList("Hermes commands", commands) { index -> composer.setText(commands[index] + " "); composer.setSelection(composer.text.length); composer.requestFocus() } }
            "${commands.size} Hermes commands"
        }
    }

    // Device assistance and help

    private fun showDeviceAssistance() {
        val enabled = DeviceAccessibilityService.current != null
        AlertDialog.Builder(this).setTitle("Device assistance")
            .setMessage((if (enabled) "BlindPilot device assistance is on. " else "BlindPilot device assistance is off. ") +
                "When on, and when you check Let the model operate this device, the model reads the accessible screen and proposes one action at a time. " +
                "You approve every action. It works alongside TalkBack and Backtalk and never changes your screen reader settings. Stop ends a task; completed actions are not undone.")
            .setPositiveButton("Open Accessibility settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Close", null).show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("BlindPilot help").setMessage(HELP).setPositiveButton("Close", null).show()
    }

    // Documents

    @Suppress("DEPRECATION")
    private fun pickAttachment() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf", "text/*", "application/json")), REQUEST_ATTACH)

    @Deprecated("Framework Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_ATTACH -> task("Adding attachment") { cancel ->
                val (name, size) = describe(uri)
                require(size <= 25L * 1024 * 1024) { "Attachments must be 25 MB or smaller." }
                val file = File(filesDir, "attachments/${UUID.randomUUID()}/" + name.replace(Regex("[\\\\/]"), "_")).apply { parentFile!!.mkdirs() }
                contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                cancel.check()
                val attachment = Attachment(name, contentResolver.getType(uri) ?: "application/octet-stream", file.canonicalPath)
                ui { pending.add(attachment); refresh() }
                "Attached $name"
            }
            REQUEST_MODEL -> task("Importing model. Choose Stop to cancel.") { cancel -> "${models.import(uri, cancel, progress("Importing")).name} imported" }
            REQUEST_EXPORT -> {
                val id = exportId
                task("Exporting conversation") { _ ->
                    val title = store.conversation(id)?.title.orEmpty()
                    val text = "# $title\n\n" + store.messages(id).joinToString("\n\n") { "## ${if (it.role == "user") "You" else "Assistant"}\n\n${it.content}" }
                    contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                    "Conversation exported"
                }
            }
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (!it.moveToFirst()) null else it.getString(0).orEmpty().ifBlank { "attachment" } to (if (it.isNull(1)) 0L else it.getLong(1))
    } ?: ("attachment" to 0L)

    companion object {
        private const val REQUEST_ATTACH = 1
        private const val REQUEST_MODEL = 2
        private const val REQUEST_EXPORT = 3
        /** Survives rotation; attachments wait here until sent. */
        private val pending = mutableListOf<Attachment>()
        private val ACTION_IDS = listOf(R.id.item_action_0, R.id.item_action_1, R.id.item_action_2, R.id.item_action_3, R.id.item_action_4,
            R.id.item_action_5, R.id.item_action_6, R.id.item_action_7, R.id.item_action_8, R.id.item_action_9, R.id.item_action_10,
            R.id.item_action_11, R.id.item_action_12, R.id.item_action_13, R.id.item_action_14, R.id.item_action_15)
        private val MODEL_SORTS = listOf("A to Z", "Z to A", "Newest first", "Oldest first")
        const val SAMSUNG_PENDING = "Samsung Galaxy AI needs Samsung's partner SDK. Your registration is pending approval. This account type becomes available in an update after Samsung grants access."
        private val HELP = """
            BlindPilot chats with cloud AI services, a remote Hermes agent, and models that run on this tablet.

            Getting started: open Menu, then Accounts, and add an account. Choose a model with the Model button. Write in the Message field and choose Send.

            Reading answers: the Messages list shows a short preview of each message. Activate a message to read all of it, select text, copy, share, or open its links and code blocks.

            Message and conversation actions: with TalkBack or Backtalk, swipe up or down on a message or conversation to choose an action, then double tap. Messages offer read all, copy, edit, edit and resend, resend, regenerate, share, links, and delete. Conversations offer open, rename, export, and delete. Long press shows the same actions as a menu.

            Message and conversation actions: with TalkBack or Backtalk, swipe up or down on a message or conversation to choose an action, then double tap. Your messages offer read all, copy, edit, edit and resend, resend, save as prompt, new chat from here, share, links, code blocks, and delete. Responses offer regenerate and edit in place of resend. Conversations offer open, rename, export, and delete. Long press shows the same actions as a menu.

            Stop ends the current task. While a task runs, a notification also offers Stop.

            On-device models: open Menu, then On-device models, to import a GGUF file or download one with its SHA-256 checksum. These models work offline.

            Device assistance: turn on BlindPilot device assistance in Android Accessibility settings, then check Let the model operate this device before sending. You approve every action. Your screen reader stays on.

            Keyboard: Control+Enter sends. Control+N starts a new chat. Control+L lists conversations. Control+M opens the menu. Escape stops a running task.
        """.trimIndent()
    }
}
