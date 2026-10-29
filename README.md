# Chatly Android SDK (Kotlin)

```kotlin
import com.chatly.Chatly
import kotlinx.coroutines.runBlocking

// `context` is optional: with one, the visitor session is persisted in
// EncryptedSharedPreferences and survives a relaunch. Without one it lives in
// memory for the life of the process.
Chatly.configure(channelPublicId = "CH_abc123", context = applicationContext)

runBlocking {
    Chatly.shared().identify(externalId = "u-42", email = "jamie@acme.io")
    Chatly.shared().track("checkout.completed", mapOf("orderId" to "O-7"))
    Chatly.shared().send("Hi!")
}
```

`send` opens a thread when there isn't one and replies on the open one when
there is. For explicit control:

```kotlin
val threads = Chatly.shared().listConversations()
Chatly.shared().openConversation(threads.first().id)   // binds realtime too
val history = Chatly.shared().listMessages(threads.first().id)
Chatly.shared().replyToConversation(threads.first().id, "any update?")
```

Live messages — one flow, stable across conversation switches:

```kotlin
lifecycleScope.launch {
    Chatly.shared().realtimeEvents().collect { envelope ->
        if (envelope.type == "message.created") { /* … */ }
    }
}
```

Attachments are a three-step upload (presign, raw bytes, then the reference on
the message), all of it behind one call:

```kotlin
val attachment = Chatly.shared().uploadAttachment(bytes, "shot.png", "image/png")
Chatly.shared().send("here it is", listOf(attachment))
```

Register the FCM token from `FirebaseMessagingService.onNewToken(...)`:

```kotlin
override fun onNewToken(token: String) {
    runBlocking { Chatly.shared().registerFcmToken(token) }
}
```

## Bundled UI

`Chatly.shared().present(activity)` opens `ChatlyActivity`, which hosts
`ChatlyFragment` (Home / Messages / Help, a `RecyclerView` thread and a
composer with attachments). Set `Chatly.shared().theme` before presenting.

### Rich blocks

A `rich` message carries `MessageRow.blocks` — a card, a product grid, a
fulfilment timeline, a comparison table, quick replies — and the bundled
thread draws them. `ChatlyBlocks.read(row.blocks)` is public if you render
your own: it validates at render time, drops what it cannot draw, and never
throws, so a server newer than the app loses the one block it does not know
rather than the message.

A pressed button goes to `MessageListAdapter.blocks.onAction` with the
message id, because this SDK has no postback or canvas transport — the host
runs its own payment sheet or screen from it. A `url` action falls back to an
`ACTION_VIEW` intent, and a quick reply is sent as an ordinary message.

## Install (Gradle)

Not on Maven Central yet — see
[the docs page](https://chatlychat.com/docs/sdk-android) for the JitPack and
vendored-source routes, and for what this SDK still does not do.

```kotlin
dependencies {
    implementation("com.chatlychat:chatly-android:0.1.0")
}
```

## Building it

```sh
./gradlew assembleRelease
./gradlew test
```

An Android SDK with platform 34 is required (`ANDROID_HOME`, or an
`sdk.dir` line in a local `local.properties`, which is gitignored).

---

## About this repository

This is a **mirror**. `chatly-android` is built from a private monorepo where the rest
of the platform, its migrations and its contract checks live, so a pull request here
cannot be merged directly — but a patch is still the fastest way to get something
fixed and we will port it with attribution.

What you can do here: read the source, run the tests, open an issue, or open a PR
and we will treat the diff as the proposal.

Issues: https://github.com/chatlychat/chatly-android/issues · Docs: https://chatlychat.com/docs
