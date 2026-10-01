package dev.memoh.core.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.memoh.core.network.AuthLease
import dev.memoh.core.network.CloudCookieStorage
import okhttp3.Cookie
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A stored account. Credentials live in encrypted preferences; everything else
 * here is safe to keep in plain preferences.
 */
@Serializable
data class StoredAccount(
    val accountId: String,
    /** Self-hosted origin, or the official cloud origin. */
    val origin: String,
    val kind: String,
    /** Empty for the official cloud, whose session is a cookie. */
    val apiPrefix: String = "",
    val userId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val teamId: String? = null,
)

/**
 * Credential storage.
 *
 * Two hard rules from the project's security notes, both enforced here rather
 * than at call sites:
 *  - tokens never touch plain preferences, logs, or saved state;
 *  - switching or signing out wipes every trace of the previous account.
 */
class CredentialStore(context: Context, private val json: Json) : CloudCookieStorage {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Monotonic account generation. Bumped on every login and logout; in-flight
     * requests hold the generation they started with, so a response from a
     * previous account cannot write into the current one's state.
     */
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    init {
        _generation.value = prefs.getLong(KEY_GENERATION, 0L)
    }

    fun accounts(): List<StoredAccount> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(StoredAccount.serializer()),
                raw,
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun activeAccountId(): String? = prefs.getString(KEY_ACTIVE_ACCOUNT, null)

    fun activeAccount(): StoredAccount? =
        activeAccountId()?.let { id -> accounts().firstOrNull { it.accountId == id } }

    override fun loadCloudCookies(): List<Cookie> = runCatching {
        val raw = prefs.getString("cloud_cookies", null) ?: return emptyList()
        json.decodeFromString<List<StoredCookie>>(raw).map { item ->
            Cookie.Builder().name(item.name).value(item.value).path(item.path)
                .expiresAt(item.expiresAt)
                .apply {
                    if (item.hostOnly) hostOnlyDomain(item.domain) else domain(item.domain)
                    if (item.secure) secure()
                    if (item.httpOnly) httpOnly()
                }.build()
        }
    }.getOrDefault(emptyList())

    override fun saveCloudCookies(cookies: List<Cookie>) {
        val items = cookies.map {
            StoredCookie(it.name, it.value, it.domain, it.path, it.expiresAt, it.secure, it.httpOnly, it.hostOnly)
        }
        prefs.edit().putString("cloud_cookies", json.encodeToString(items)).apply()
    }

    /** Stores an account and makes it active, bumping the generation. */
    fun saveAccount(account: StoredAccount) {
        val updated = accounts().filterNot { it.accountId == account.accountId } + account
        prefs.edit()
            .putString(
                KEY_ACCOUNTS,
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(StoredAccount.serializer()),
                    updated,
                ),
            )
            .putString(KEY_ACTIVE_ACCOUNT, account.accountId)
            .apply()
        bumpGeneration()
    }

    fun setActiveAccount(accountId: String) {
        prefs.edit().putString(KEY_ACTIVE_ACCOUNT, accountId).apply()
        bumpGeneration()
    }

    /** Removes an account and its credentials; clears the active pointer if it matched. */
    fun removeAccount(accountId: String) {
        val remaining = accounts().filterNot { it.accountId == accountId }
        val editor = prefs.edit()
            .putString(
                KEY_ACCOUNTS,
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(StoredAccount.serializer()),
                    remaining,
                ),
            )
            .remove(tokenKey(accountId))
            .remove(expiresKey(accountId))
        if (activeAccountId() == accountId) editor.remove(KEY_ACTIVE_ACCOUNT)
        editor.apply()
        bumpGeneration()
    }

    fun token(accountId: String): String? = prefs.getString(tokenKey(accountId), null)

    fun expiresAt(accountId: String): Long? =
        prefs.getLong(expiresKey(accountId), 0L).takeIf { it > 0L }

    fun saveToken(accountId: String, token: String, expiresAtMillis: Long?) {
        prefs.edit()
            .putString(tokenKey(accountId), token)
            .putLong(expiresKey(accountId), expiresAtMillis ?: 0L)
            .apply()
    }

    fun clearToken(accountId: String) {
        prefs.edit().remove(tokenKey(accountId)).remove(expiresKey(accountId)).apply()
    }

    /** Lease for the active account, or null when signed out. */
    fun lease(): AuthLease? {
        val id = activeAccountId() ?: return null
        val token = token(id) ?: return null
        return AuthLease(token = token, expiresAtMillis = expiresAt(id), generation = _generation.value)
    }

    /** Wipes credentials for every account, keeping the account list metadata. */
    fun clearAllTokens() {
        val editor = prefs.edit()
        accounts().forEach { editor.remove(tokenKey(it.accountId)).remove(expiresKey(it.accountId)) }
        editor.apply()
    }

    private fun bumpGeneration() {
        val next = _generation.value + 1
        _generation.value = next
        prefs.edit().putLong(KEY_GENERATION, next).apply()
    }

    private fun tokenKey(accountId: String) = "token_$accountId"
    private fun expiresKey(accountId: String) = "expires_$accountId"

    private companion object {
        const val FILE_NAME = "memoh_credentials"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_ACTIVE_ACCOUNT = "active_account"
        const val KEY_GENERATION = "generation"
    }
}

@Serializable
private data class StoredCookie(
    val name: String, val value: String, val domain: String, val path: String,
    val expiresAt: Long, val secure: Boolean, val httpOnly: Boolean, val hostOnly: Boolean,
)
