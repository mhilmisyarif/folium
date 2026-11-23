package dev.mangareader.scrobbling

import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

// ---------------------------------------------------------------- AniList (implicit grant + GraphQL)

class AniListScrobbler(client: OkHttpClient, prefs: SharedPreferences) : Scrobbler(client, prefs) {
    override val id = "anilist"
    override val name = "AniList"
    override val credentialFields = listOf("client_id" to "Client ID")

    override fun authUrl() = "https://anilist.co/api/v2/oauth/authorize".toHttpUrl().newBuilder()
        .addQueryParameter("client_id", credential("client_id"))
        .addQueryParameter("response_type", "token").build().toString()

    override suspend fun handleRedirect(uri: Uri) {
        // Implicit grant returns the token in the fragment: #access_token=…&token_type=Bearer&expires_in=…
        val token = Uri.parse("?" + uri.encodedFragment.orEmpty()).getQueryParameter("access_token")
        accessToken = requireNotNull(token) { "AniList tidak mengirim token" }
    }

    private suspend fun graphql(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val root = JSONObject(call(authed("https://graphql.anilist.co").post(json(body)).build()))
        root.optJSONArray("errors")?.let { throw java.io.IOException("AniList: " + it.optJSONObject(0)?.optString("message")) }
        return root.getJSONObject("data")
    }

    override suspend fun search(query: String): List<ScrobblerManga> {
        val q = "query(\$s:String){Page(perPage:20){media(search:\$s,type:MANGA){id title{romaji english} coverImage{medium}}}}"
        return parseAniListSearch(graphql(q, JSONObject().put("s", query)))
    }

    override suspend fun updateProgress(targetId: String, chapter: Int) {
        val q = "mutation(\$id:Int,\$p:Int){SaveMediaListEntry(mediaId:\$id,progress:\$p,status:CURRENT){id}}"
        graphql(q, JSONObject().put("id", targetId.toInt()).put("p", chapter))
    }
}

internal fun parseAniListSearch(data: JSONObject): List<ScrobblerManga> {
    val media = data.getJSONObject("Page").getJSONArray("media")
    return (0 until media.length()).map { media.getJSONObject(it) }.map { m ->
        val t = m.getJSONObject("title")
        ScrobblerManga(
            id = m.getLong("id").toString(),
            title = t.optString("english").takeUnless { t.isNull("english") || it.isBlank() } ?: t.optString("romaji"),
            coverUrl = m.optJSONObject("coverImage")?.optString("medium"),
        )
    }
}

// ---------------------------------------------------------------- MyAnimeList (OAuth2 + PKCE "plain")

class MalScrobbler(client: OkHttpClient, prefs: SharedPreferences) : Scrobbler(client, prefs) {
    override val id = "mal"
    override val name = "MyAnimeList"
    override val credentialFields = listOf("client_id" to "Client ID")

    override fun authUrl(): String {
        val verifier = randomVerifier()
        prefs.edit { putString("$id.verifier", verifier) }
        return "https://myanimelist.net/v1/oauth2/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", credential("client_id"))
            .addQueryParameter("code_challenge", verifier) // MAL only supports the "plain" method
            .addQueryParameter("code_challenge_method", "plain")
            .addQueryParameter("redirect_uri", redirectUri)
            .build().toString()
    }

    override suspend fun handleRedirect(uri: Uri) {
        val code = requireNotNull(uri.getQueryParameter("code")) { "MAL tidak mengirim kode" }
        val form = FormBody.Builder()
            .add("client_id", credential("client_id"))
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", prefs.getString("$id.verifier", null).orEmpty())
            .add("redirect_uri", redirectUri)
            .build()
        val rsp = JSONObject(call(Request.Builder().url("https://myanimelist.net/v1/oauth2/token").post(form).build()))
        accessToken = rsp.getString("access_token")
        refreshToken = rsp.optString("refresh_token")
    }

    override suspend fun search(query: String): List<ScrobblerManga> {
        val url = "https://api.myanimelist.net/v2/manga".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.take(64)).addQueryParameter("limit", "20").build().toString()
        val data = JSONObject(call(authed(url).build())).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it).getJSONObject("node") }.map {
            ScrobblerManga(it.getLong("id").toString(), it.getString("title"), it.optJSONObject("main_picture")?.optString("medium"))
        }
    }

    override suspend fun updateProgress(targetId: String, chapter: Int) {
        val form = FormBody.Builder().add("status", "reading").add("num_chapters_read", chapter.toString()).build()
        call(authed("https://api.myanimelist.net/v2/manga/$targetId/my_list_status").patch(form).build())
    }
}

// ---------------------------------------------------------------- Kitsu (password grant + JSON:API)

class KitsuScrobbler(client: OkHttpClient, prefs: SharedPreferences) : Scrobbler(client, prefs) {
    override val id = "kitsu"
    override val name = "Kitsu"
    override val usesPasswordLogin = true
    override val credentialFields = listOf("client_id" to "Client ID", "client_secret" to "Client Secret")
    // Kitsu's public first-party credentials (the same ones Kotatsu and other open-source clients ship).
    override val credentialDefaults = mapOf(
        "client_id" to "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd",
        "client_secret" to "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151",
    )
    private val api = "https://kitsu.app/api/edge"
    private val jsonApi = "application/vnd.api+json"

    override suspend fun loginWithPassword(username: String, password: String) {
        val form = FormBody.Builder()
            .add("grant_type", "password").add("username", username).add("password", password)
            .add("client_id", credential("client_id")).add("client_secret", credential("client_secret"))
            .build()
        val rsp = JSONObject(call(Request.Builder().url("https://kitsu.app/api/oauth/token").post(form).build()))
        accessToken = rsp.getString("access_token")
        refreshToken = rsp.optString("refresh_token")
        val me = JSONObject(call(authed("$api/users?filter[self]=true").build())).getJSONArray("data")
        prefs.edit { putString("$id.user", me.getJSONObject(0).getString("id")) }
    }

    override suspend fun search(query: String): List<ScrobblerManga> {
        val url = "$api/manga".toHttpUrl().newBuilder().addQueryParameter("filter[text]", query)
            .addQueryParameter("page[limit]", "20").build().toString()
        val data = JSONObject(call(authed(url).build())).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it) }.map {
            val a = it.getJSONObject("attributes")
            ScrobblerManga(it.getString("id"), a.optString("canonicalTitle"), a.optJSONObject("posterImage")?.optString("small"))
        }
    }

    override suspend fun updateProgress(targetId: String, chapter: Int) {
        val userId = requireNotNull(prefs.getString("$id.user", null)) { "Login Kitsu dulu" }
        val find = "$api/library-entries".toHttpUrl().newBuilder()
            .addQueryParameter("filter[userId]", userId).addQueryParameter("filter[mangaId]", targetId).build().toString()
        val existing = JSONObject(call(authed(find).build())).getJSONArray("data").optJSONObject(0)
        val attrs = JSONObject().put("progress", chapter).put("status", "current")
        if (existing != null) {
            val body = JSONObject().put("data", JSONObject().put("type", "libraryEntries").put("id", existing.getString("id")).put("attributes", attrs))
            call(authed("$api/library-entries/${existing.getString("id")}").patch(json(body.toString(), jsonApi)).build())
        } else {
            val rel = JSONObject()
                .put("user", JSONObject().put("data", JSONObject().put("type", "users").put("id", userId)))
                .put("manga", JSONObject().put("data", JSONObject().put("type", "manga").put("id", targetId)))
            val body = JSONObject().put("data", JSONObject().put("type", "libraryEntries").put("attributes", attrs).put("relationships", rel))
            call(authed("$api/library-entries").post(json(body.toString(), jsonApi)).build())
        }
    }
}

// ---------------------------------------------------------------- Shikimori (OAuth2 code + client secret)

class ShikimoriScrobbler(client: OkHttpClient, prefs: SharedPreferences) : Scrobbler(client, prefs) {
    override val id = "shikimori"
    override val name = "Shikimori"
    override val credentialFields = listOf("client_id" to "Client ID", "client_secret" to "Client Secret")
    private val base = "https://shikimori.one"

    // Shikimori rejects requests whose User-Agent does not name the OAuth app.
    private fun sauthed(url: String) = authed(url).header("User-Agent", UA)
    private companion object { const val UA = "MangaReaderLite" }

    override fun authUrl() = "$base/oauth/authorize".toHttpUrl().newBuilder()
        .addQueryParameter("client_id", credential("client_id"))
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("scope", "user_rates").build().toString()

    override suspend fun handleRedirect(uri: Uri) {
        val code = requireNotNull(uri.getQueryParameter("code")) { "Shikimori tidak mengirim kode" }
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code").add("client_id", credential("client_id"))
            .add("client_secret", credential("client_secret")).add("code", code).add("redirect_uri", redirectUri)
            .build()
        val rsp = JSONObject(call(Request.Builder().url("$base/oauth/token").header("User-Agent", UA).post(form).build()))
        accessToken = rsp.getString("access_token")
        refreshToken = rsp.optString("refresh_token")
        val me = JSONObject(call(sauthed("$base/api/users/whoami").build()))
        prefs.edit { putString("$id.user", me.getLong("id").toString()) }
    }

    override suspend fun search(query: String): List<ScrobblerManga> {
        val url = "$base/api/mangas".toHttpUrl().newBuilder().addQueryParameter("search", query)
            .addQueryParameter("limit", "20").build().toString()
        val arr = JSONArray(call(sauthed(url).build()))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            ScrobblerManga(it.getLong("id").toString(), it.optString("name"), it.optJSONObject("image")?.optString("preview")?.let { p -> base + p })
        }
    }

    override suspend fun updateProgress(targetId: String, chapter: Int) {
        val userId = requireNotNull(prefs.getString("$id.user", null)) { "Login Shikimori dulu" }
        val find = "$base/api/v2/user_rates".toHttpUrl().newBuilder().addQueryParameter("user_id", userId)
            .addQueryParameter("target_id", targetId).addQueryParameter("target_type", "Manga").build().toString()
        val existing = JSONArray(call(sauthed(find).build())).optJSONObject(0)
        val rate = JSONObject().put("chapters", chapter).put("status", "watching") // Shikimori uses "watching" for manga too
        if (existing != null) {
            call(sauthed("$base/api/v2/user_rates/${existing.getLong("id")}").patch(json(JSONObject().put("user_rate", rate).toString())).build())
        } else {
            rate.put("user_id", userId.toLong()).put("target_id", targetId.toLong()).put("target_type", "Manga")
            call(sauthed("$base/api/v2/user_rates").post(json(JSONObject().put("user_rate", rate).toString())).build())
        }
    }
}

private fun randomVerifier(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val rnd = SecureRandom()
    return (1..96).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}
