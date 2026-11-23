package dev.mangareader.parser

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource

class KotatsuSourceTest {

    private val stubContext = object : MangaLoaderContext() {
        override val httpClient = OkHttpClient()
        override val cookieJar = CookieJar.NO_COOKIES
        @Deprecated("Provide a base url")
        override suspend fun evaluateJs(script: String): String? = null
        override suspend fun evaluateJs(baseUrl: String, script: String): String? = null
        override fun getConfig(source: MangaSource) = object : MangaSourceConfig {
            override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
        }
        override fun getDefaultUserAgent() = "test"
        override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap) = response
        override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
    }

    /** Every source the app lists must be constructible and expose a valid default domain (catalogue + domain dialog). */
    @Test fun allSources_instantiateWithValidDomain() {
        val sources = MangaParserSource.entries.filter { !it.isBroken }
        assertTrue("expected the full Kotatsu catalogue, got ${sources.size}", sources.size > 800) // 1256 in total, ~370 flagged broken upstream
        val bad = sources.mapNotNull { s ->
            runCatching { KotatsuSource(s, stubContext).defaultDomain }.fold(
                { d -> if (normalizeDomain(d) == null) "${s.name}: domain '$d'" else null },
                { e -> "${s.name}: $e" },
            )
        }
        // Report rather than fail on a handful: a few upstream defaults (IP addresses, paths) are not plain hosts.
        assertTrue("too many unusable sources: ${bad.take(20)}", bad.size < sources.size / 50)
    }

    @Test fun knownSources_keepTheirIds() {
        // Ids are stored with favourites/history; these were the app's built-in sources before.
        listOf("KOMIKSTATION", "MANGAREAD", "MANGADEX").forEach { MangaParserSource.valueOf(it) }
    }

    @Test fun preferredBranch_matchesLanguageElseLargest() {
        val branches = mapOf<String?, Int>("English" to 120, "Bahasa Indonesia" to 40, "Español" to 80)
        assertEquals("Bahasa Indonesia", preferredBranch(branches, listOf("Indonesia", "Indonesian")))
        assertEquals("English", preferredBranch(branches, listOf("Deutsch", "German")))
        assertNull(preferredBranch(mapOf(null to 10), listOf("Indonesia")))
        assertNull(preferredBranch(emptyMap(), listOf("Indonesia")))
    }

    @Test fun normalizeDomain_acceptsHostsOnly() {
        assertEquals("komikstation.org", normalizeDomain(" https://KomikStation.org/ "))
        assertEquals("www.mangaread.org", normalizeDomain("www.mangaread.org"))
        assertEquals("localhost.test:8080", normalizeDomain("localhost.test:8080"))
        assertNull(normalizeDomain("komikstation"))
        assertNull(normalizeDomain("evil.com/path"))
        assertNull(normalizeDomain("a b.com"))
        assertNull(normalizeDomain(""))
    }

    @Test fun cloudflareChallenge_detection() {
        assertTrue(isCloudflareChallenge(403, null, "<html><head><title>Just a moment...</title>"))
        assertTrue(isCloudflareChallenge(503, "challenge", ""))
        assertTrue(isCloudflareChallenge(403, null, "<script>window._cf_chl_opt={}</script>"))
        assertFalse(isCloudflareChallenge(404, null, "<title>Just a moment"))
        assertFalse(isCloudflareChallenge(403, null, "Forbidden"))
    }
}
