package dev.memoh.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import java.io.IOException

class GitHubReleasesTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }
    private fun api() = GitHubReleases(OkHttpClient(), Json, server.url("/releases/latest").toString())

    @Test fun `numeric versions handle two digit releases and prereleases`() {
        assertTrue(compareReleaseVersions("v0.1.12", "0.1.9")!! > 0)
        assertEquals(0, compareReleaseVersions("v1.2.0+build", "1.2"))
        assertTrue(compareReleaseVersions("1.0", "1.0-rc.2")!! > 0)
        assertTrue(compareReleaseVersions("1.0-rc.10", "1.0-rc.2")!! > 0)
        assertTrue(compareReleaseVersions("0.1.11", "0.1.12")!! < 0)
        assertNull(compareReleaseVersions("latest", "1.0"))
    }

    @Test fun `latest release uses uploaded signed APK and public unauthenticated request`() = runTest {
        server.enqueue(MockResponse().setBody("""{"tag_name":"v0.1.12","name":"Mehomo 0.1.12","body":"## 日程\n完整执行记录","html_url":"https://github.com/anamaxlec/Mehomo/releases/tag/v0.1.12","draft":false,"prerelease":false,"assets":[{"name":"Mehomo-debug.apk","state":"uploaded","browser_download_url":"debug"},{"name":"Mehomo-v0.1.12-release.apk","state":"new","browser_download_url":"unfinished"},{"name":"Mehomo-v0.1.12-release.apk","state":"uploaded","size":123456,"browser_download_url":"https://github.com/anamaxlec/Mehomo/releases/download/v0.1.12/Mehomo-v0.1.12-release.apk"}]}"""))
        val release = requireNotNull(api().latest())
        assertEquals("0.1.12", release.version)
        assertEquals(123456L, release.apkSize)
        assertTrue(release.apkUrl!!.endsWith("-release.apk"))
        assertTrue(release.notes.contains("\n"))
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    @Test fun `draft prerelease and missing release do not become update candidates`() = runTest {
        for (flag in listOf("draft", "prerelease")) {
            server.enqueue(MockResponse().setBody("""{"tag_name":"v9.0.0","$flag":true}"""))
            assertNull(api().latest())
        }
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(api().latest())
    }

    @Test fun `release without APK still offers its release page`() = runTest {
        server.enqueue(MockResponse().setBody("""{"tag_name":"v0.1.12","html_url":"https://github.com/anamaxlec/Mehomo/releases/tag/v0.1.12","assets":[]}"""))
        val release = requireNotNull(api().latest())
        assertNull(release.apkUrl); assertTrue(release.pageUrl.contains("/releases/tag/"))
    }

    @Test fun `rate limit and server failures remain retryable errors`() = runTest {
        for (code in listOf(403, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))
            try { api().latest(); fail("Expected failure for $code") }
            catch (e: IOException) { assertTrue(e.message!!.isNotBlank()) }
        }
    }
}
