package com.chatly.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted on-device store for the visitor session token returned by
 * `/v1/widget/init`. Falls back to a regular (unencrypted) preference file when
 * the keystore is unavailable (e.g. degraded devices / unit tests).
 *
 * One file per [publicId] keeps multi-channel apps isolated.
 */
public class TokenStore internal constructor(
    private val prefs: SharedPreferences,
) : VisitorTokenStorage {
    /** Read the persisted visitor token, if any. */
    public val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    /** Replace the persisted visitor token. Pass `null` to clear. */
    public fun setToken(value: String?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
            apply()
        }
    }

    /** Wipe everything for this publicId. */
    public fun clear() {
        prefs.edit().clear().apply()
    }

    // `VisitorTokenStorage` — the names the client calls. The three above are
    // the published surface and stay exactly as they were.
    override fun read(): String? = token

    override fun write(value: String?): Unit = setToken(value)

    override fun wipe(): Unit = clear()

    public companion object {
        private const val KEY_TOKEN = "visitor_token"

        /** Build a [TokenStore] scoped to [publicId]. */
        public fun create(context: Context, publicId: String): TokenStore {
            val name = "chatly-sdk-${publicId}"
            val prefs: SharedPreferences = runCatching {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    name,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrElse {
                context.applicationContext.getSharedPreferences("$name-fallback", Context.MODE_PRIVATE)
            }
            return TokenStore(prefs)
        }
    }
}
