package dev.mangareader.scrobbling

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Manga on a tracking service (Kotatsu: ScrobblerManga). */
data class ScrobblerManga(val id: String, val title: String, val coverUrl: String?)

class ScrobblerAuthException(service: String) : IOException("Sesi $service berakhir, silakan login ulang")

/**
 * A reading-progress tracking service (Kotatsu: scrobbling/anilist, mal, kitsu, shikimori).
 * Client credentials are entered by the user (each service requires its own registered app);
 * redirect URI to register: mangareader://oauth/<id>
 */
abstract class Scrobbler(
    protected val client: OkHttpClient,
    protected val prefs: SharedPreferences,
) {
    abstract val id: String
    abstract val name: String
    /** Settings the user must fill in, e.g. "client_id" → "Client ID". */
    abstract val credentialFields: List<Pair<String, String>>
    /** Default values for credentials (Kitsu ships public ones). */
    open val credentialDefaults: Map<String, String> = emptyMap()
    /** True for password login (Kitsu); false for browser OAuth. */
    open val usesPasswordLogin = false

    val redirectUri get() = "mangareader://oauth/$id"

    fun credential(key: String): String =
        prefs.getString("$id.$key", null)?.takeIf { it.isNotBlank() } ?: credentialDefaults[key].orEmpty()

    fun setCredential(key: String, value: String) = prefs.edit { putString("$id.$key", value.trim()) }

    protected var accessToken: String?
        get() = prefs.getString("$id.token", null)
        set(v) = prefs.edit { putString("$id.token", v) }

    protected var refreshToken: String?
        get() = prefs.getString("$id.refresh", null)
        set(v) = prefs.edit { putString("$id.refresh", v) }

    val isAuthorized get() = accessToken != null
    val isConfigured get() = credentialFields.all { credential(it.first).isNotBlank() }

    fun logout() = prefs.edit { remove("$id.token"); remove("$id.refresh"); remove("$id.user") }

    /** Browser URL that starts OAuth; null for password-login services. */
    open fun authUrl(): String? = null

    /** Handles mangareader://oauth/<id>?code=… (or #access_token=… for implicit grant). */
    open suspend fun handleRedirect(uri: android.net.Uri): Unit = throw UnsupportedOperationException()

    open suspend fun loginWithPassword(username: String, password: String): Unit = throw UnsupportedOperationException()

    abstract suspend fun search(query: String): List<ScrobblerManga>

    /** Sets read chapters to [chapter] and status to "reading". */
    abstract suspend fun updateProgress(targetId: String, chapter: Int)

    protected suspend fun call(request: Request): String = runInterruptible(Dispatchers.IO) {
        client.newCall(request).execute().use { rsp ->
            val body = rsp.body?.string().orEmpty()
            if (rsp.code == 401) {
                // shortcut: no automatic refresh-token rotation; the user logs in again when a token expires.
                logout()
                throw ScrobblerAuthException(name)
            }
            if (!rsp.isSuccessful) throw IOException("$name: HTTP ${rsp.code} ${body.take(200)}")
            body
        }
    }

    protected fun authed(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${accessToken ?: throw ScrobblerAuthException(name)}")

    protected fun json(body: String, type: String = "application/json"): RequestBody = body.toRequestBody(type.toMediaType())
}

/** Extracts the chapter number used for progress, e.g. "Ch. 12.5 – Title" → 12. */
fun chapterNumber(name: String): Int? =
    Regex("""(?i)(?:ch(?:apter)?\.?\s*)(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
