# Chatly Android SDK (Kotlin)

```kotlin
import com.chatly.Chatly
import kotlinx.coroutines.runBlocking

Chatly.configure("CH_abc123")

runBlocking {
    Chatly.shared().identify(externalId = "u-42", email = "jamie@acme.io")
    Chatly.shared().track("checkout.completed", mapOf("orderId" to "O-7"))
    Chatly.shared().send("Hi!")
}
```

Register the FCM token from `FirebaseMessagingService.onNewToken(...)`:

```kotlin
override fun onNewToken(token: String) {
    runBlocking { Chatly.shared().registerFcmToken(token) }
}
```

## Install (Gradle)

```kotlin
dependencies {
    implementation("com.chatly:sdk-kotlin:0.1.0")
}
```

---

## About this repository

This is a **mirror**. `chatly-android` is built from a private monorepo where the rest
of the platform, its migrations and its contract checks live, so a pull request here
cannot be merged directly — but a patch is still the fastest way to get something
fixed and we will port it with attribution.

What you can do here: read the source, run the tests, open an issue, or open a PR
and we will treat the diff as the proposal.

Issues: https://github.com/chatlychat/chatly-android/issues · Docs: https://chatlychat.com/docs
