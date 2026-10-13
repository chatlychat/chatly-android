package com.chatly.storage

/**
 * Where the visitor session token lives between calls.
 *
 * An interface rather than [TokenStore] directly because [TokenStore] needs an
 * Android `Context` and a keystore, neither of which exists in a JVM unit
 * test — and the request bodies that carry this token are exactly what the
 * tests have to be able to inspect.
 */
public interface VisitorTokenStorage {
    /** The persisted visitor token, or null when this device has none yet. */
    public fun read(): String?

    /** Replace it. `null` clears. */
    public fun write(value: String?)

    /** Forget the session entirely. */
    public fun wipe()
}

/**
 * Process-lifetime storage. What [com.chatly.Chatly.configure] uses when it is
 * given no `Context`: the session then lasts as long as the process and a
 * relaunch is a new visitor, which is the behaviour this SDK has always had.
 */
public class InMemoryVisitorTokenStorage : VisitorTokenStorage {
    @Volatile private var value: String? = null

    override fun read(): String? = value

    override fun write(value: String?) {
        this.value = value
    }

    override fun wipe() {
        value = null
    }
}
