package com.chatly.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chatly.Chatly
import com.chatly.R
import com.chatly.models.Attachment
import com.chatly.models.MessageRow
import com.chatly.realtime.RealtimeEnvelope
import com.chatly.ui.adapters.MessageListAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Top-level chat surface. Hosts a 3-tab strip (Home / Messages / Help) wired to
 * the widget endpoints exposed by [Chatly]. Designed to be hosted inside
 * [ChatlyActivity] or any customer activity.
 */
public class ChatlyFragment : Fragment() {

    private lateinit var tabsRow: LinearLayout
    private lateinit var content: ViewGroup
    private lateinit var headerTitle: TextView
    private var currentConversationId: String? = null

    /** Held across tab switches so realtime appends survive a re-show. */
    private var messagesAdapter: MessageListAdapter? = null
    private var messagesRecycler: RecyclerView? = null

    /** Weak ref to the currently visible composer so the picker callback can
     *  stage the resulting attachment regardless of how the user navigates. */
    private var activeComposer: ChatlyComposerView? = null

    /**
     * Registers `Intent.ACTION_GET_CONTENT` (via `GetContent`) at view-create
     * time — Android requires result launchers to be created before the
     * activity reaches STARTED.
     */
    private val pickFileLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            uploadPickedUri(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.chatly_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tabsRow = view.findViewById(R.id.chatly_tabs)
        content = view.findViewById(R.id.chatly_content)
        headerTitle = view.findViewById(R.id.chatly_header_title)

        applyTheme(view)
        wireTabs()
        showHome()

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { Chatly.shared().initSession() }
                .onSuccess { headerTitle.text = it.workspace.name }
        }
    }

    override fun onResume() {
        super.onResume()
        // Collect realtime envelopes while the view is at least STARTED so we
        // pause subscription work when the fragment goes to the background
        // without leaking the coroutine.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Chatly.shared().realtimeEvents().collect { envelope ->
                    handleRealtime(envelope)
                }
            }
        }
    }

    /**
     * Forward `message.created` envelopes for the active conversation into
     * the adapter. Other event types (typing, presence, AI suggestions, ...)
     * are intentionally ignored here — call sites that need them should
     * collect [Chatly.realtimeEvents] directly.
     */
    private fun handleRealtime(envelope: RealtimeEnvelope) {
        if (envelope.type != "message.created") return
        val payload = envelope.payload as? JsonObject ?: return
        val convoId = payload.str("conversationId") ?: return
        if (convoId != currentConversationId) return
        val attachmentsJson = payload["attachments"] as? kotlinx.serialization.json.JsonArray
        val attachments: List<Attachment> = attachmentsJson?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            Attachment(
                id = obj.str("id").orEmpty(),
                url = obj.str("url").orEmpty(),
                filename = obj.str("filename").orEmpty(),
                mimeType = obj.str("mimeType") ?: "application/octet-stream",
                sizeBytes = (obj["sizeBytes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            )
        } ?: emptyList()
        val row = MessageRow(
            id = payload.str("id").orEmpty(),
            authorType = payload.str("authorType") ?: "agent",
            kind = payload.str("kind") ?: "text",
            body = payload.str("body").orEmpty(),
            attachments = attachments,
            createdAt = payload.str("createdAt").orEmpty(),
        )
        val adapter = messagesAdapter ?: return
        if (adapter.append(row)) {
            messagesRecycler?.scrollToPosition(adapter.itemCount - 1)
        }
    }

    /** Null-safe primitive accessor. Returns null if absent or non-primitive. */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNullSafe()

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

    private fun applyTheme(view: View) {
        val theme = Chatly.shared().theme
        view.setBackgroundColor(theme.bg)
        view.findViewById<View>(R.id.chatly_header).setBackgroundColor(theme.primary)
        headerTitle.setTextColor(theme.bg)
    }

    private fun wireTabs() {
        view?.findViewById<View>(R.id.tab_home)?.setOnClickListener { showHome() }
        view?.findViewById<View>(R.id.tab_messages)?.setOnClickListener { showMessages() }
        view?.findViewById<View>(R.id.tab_help)?.setOnClickListener { showHelp() }
    }

    private fun showHome() {
        content.removeAllViews()
        val tv = TextView(requireContext()).apply {
            text = getString(R.string.chatly_home_welcome)
            setPadding(48, 48, 48, 48)
            setTextColor(Chatly.shared().theme.fg)
        }
        content.addView(tv)
    }

    private fun showMessages() {
        content.removeAllViews()
        val adapter = messagesAdapter ?: MessageListAdapter(emptyList()).also { messagesAdapter = it }
        val recycler = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
        messagesRecycler = recycler
        val composer = ChatlyComposerView(requireContext()).apply {
            onSend = { text, attachments -> sendVisitorMessage(text, attachments, recycler) }
            onAttachClick = { pickFileLauncher.launch("*/*") }
        }
        activeComposer = composer
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(recycler, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(composer)
        }
        content.addView(column)
        loadOrStartConversation(recycler)
    }

    private fun showHelp() {
        content.removeAllViews()
        val tv = TextView(requireContext()).apply {
            text = getString(R.string.chatly_help_hint)
            setPadding(48, 48, 48, 48)
            setTextColor(Chatly.shared().theme.fg)
        }
        content.addView(tv)
    }

    private fun loadOrStartConversation(recycler: RecyclerView) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val conversations = Chatly.shared().listConversations()
                val convo = conversations.firstOrNull()
                if (convo != null) {
                    currentConversationId = convo.id
                    /*
                     * Tell the client, not just ourselves. A realtime token
                     * names ONE conversation, so the socket has to be rebound
                     * when the thread changes — without this the fragment
                     * renders history and then waits for envelopes on a
                     * socket that was never opened.
                     */
                    Chatly.shared().openConversation(convo.id)
                    val messages = Chatly.shared().listMessages(convo.id)
                    bind(recycler, messages)
                }
            }
        }
    }

    private fun sendVisitorMessage(
        text: String,
        attachments: List<Attachment>,
        recycler: RecyclerView,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val convoId = currentConversationId
                if (convoId == null) {
                    val started = Chatly.shared().startConversation(text, attachments)
                    currentConversationId = started.conversation.id
                } else {
                    Chatly.shared().replyToConversation(convoId, text, attachments)
                }
                val convoIdAfter = currentConversationId
                if (convoIdAfter != null) {
                    val refreshed = Chatly.shared().listMessages(convoIdAfter)
                    bind(recycler, refreshed)
                }
            }
        }
    }

    /**
     * Reads the picked content into a byte array, calls the widget upload
     * endpoint, and stages the resulting [Attachment] on the active composer.
     * Spinner state is driven via [ChatlyComposerView.setUploading] so the
     * chip strip is responsive even on slow networks.
     */
    private fun uploadPickedUri(uri: Uri) {
        val composer = activeComposer ?: return
        composer.setUploading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val resolver = requireContext().contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val filename = queryDisplayName(uri) ?: "upload"
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("could not open content")
                }
                Chatly.shared().uploadAttachment(bytes, filename, mime)
            }.onSuccess { attachment ->
                composer.addAttachment(attachment)
            }
            composer.setUploading(false)
        }
    }

    /** Best-effort DISPLAY_NAME lookup on a content:// uri. */
    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            requireContext().contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun bind(recycler: RecyclerView, messages: List<MessageRow>) {
        val adapter = messagesAdapter ?: MessageListAdapter(emptyList()).also {
            messagesAdapter = it
            recycler.adapter = it
        }
        adapter.submit(messages)
        if (messages.isNotEmpty()) recycler.scrollToPosition(messages.size - 1)
    }

    public companion object {
        /** Build a fresh instance — preferred over `ChatlyFragment()` directly. */
        public fun newInstance(): ChatlyFragment = ChatlyFragment()
    }
}
