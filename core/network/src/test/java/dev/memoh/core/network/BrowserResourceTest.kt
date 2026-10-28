package dev.memoh.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BrowserResourceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun endpoint(server: MockWebServer, cloud: Boolean = false) = ServerEndpoint(
        if (cloud) ServerKind.OfficialCloud else ServerKind.SelfHosted,
        server.url("/").toString().trimEnd('/'), "", "")
    private fun cloud(server: MockWebServer) = CloudAuth(OkHttpClient(), json, endpoint(server, true), object : CloudCookieStorage {
        override fun loadCloudCookies() = listOf(Cookie.parse(server.url("/"), "session=fixture-only; Path=/; HttpOnly; Max-Age=3600")!!)
        override fun saveCloudCookies(cookies: List<Cookie>) = Unit
    }).apply { teamId = "fixture-team" }

    @Test fun `Cloud document CSS and JS requests use the native cookie and team session`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = MemohApi(OkHttpClient(), endpoint(server, true), json, cloudAuth = cloud(server))
            val page = server.url("/preview/session/index.html").toString()
            for ((path, type) in listOf("index.html" to "text/html", "theme.css" to "text/css", "app.js" to "text/javascript")) {
                server.enqueue(MockResponse().setHeader("Content-Type", "$type; charset=utf-8").setBody("resource-$path"))
                api.browserResource(page, server.url("/preview/session/$path").toString(), headers = mapOf(
                    "Authorization" to "untrusted", "Cookie" to "untrusted", "X-Team-Id" to "other-team", "Accept" to type,
                )).use { response -> assertEquals("resource-$path", response.body.string()) }
                val request = server.takeRequest()
                assertEquals("session=fixture-only", request.getHeader("Cookie"))
                assertEquals("fixture-team", request.getHeader("X-Team-Id"))
                assertEquals(endpoint(server).origin, request.getHeader("Origin"))
                assertNull(request.getHeader("Authorization"))
                assertEquals(type, request.getHeader("Accept"))
            }
        }
    }

    @Test fun `a public preview origin gets no account cookies headers or bearer`() = runBlocking {
        MockWebServer().use { account -> MockWebServer().use { preview ->
            account.start(); preview.start()
            // A domain cookie would otherwise match this host on every port.
            val api = MemohApi(cloud(account).http, endpoint(account, true), json, cloudAuth = cloud(account))
            preview.enqueue(MockResponse().setBody("public preview"))
            val page = preview.url("/").toString()
            api.browserResource(page, page).use { assertEquals("public preview", it.body.string()) }
            val request = preview.takeRequest()
            for (name in listOf("Cookie", "Authorization", "Origin", "X-Team-Id")) assertNull(name, request.getHeader(name))
            assertEquals(0, account.requestCount)
        } }
    }

    @Test fun `preview redirects preserve authentication within their origin and return the final URL`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = MemohApi(OkHttpClient(), endpoint(server, true), json, cloudAuth = cloud(server))
            val page = server.url("/preview/start").toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "./nested/index.html"))
            server.enqueue(MockResponse().setBody("redirected"))
            api.browserResource(page, page).use {
                assertEquals("/preview/nested/index.html", it.request.url.encodedPath)
                assertEquals("redirected", it.body.string())
            }
            repeat(2) { assertEquals("session=fixture-only", server.takeRequest().getHeader("Cookie")) }
        }
    }

    @Test fun `cross origin redirects never receive account credentials`() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val api = MemohApi(OkHttpClient(), endpoint(server, true), json, cloudAuth = cloud(server))
            val page = server.url("/preview/").toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/")))
            try { api.browserResource(page, page).close(); fail("Must reject a different origin") }
            catch (_: UserFacingException) { }
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun `Cloud launch establishes the preview origin with delegated cookies only`() = runBlocking {
        MockWebServer().use { account -> MockWebServer().use { preview ->
            account.start(); preview.start()
            val api = MemohApi(OkHttpClient(), endpoint(account, true), json, cloudAuth = cloud(account))
            val launch = account.url("/api/v1/browser-sessions/${"a".repeat(32)}/launch").toString()
            val client = api.browserPreview(launch)
            account.enqueue(MockResponse().setResponseCode(302).setHeader("Location", preview.url("/index.html"))
                .addHeader("Set-Cookie", "preview=delegated-only; Path=/; HttpOnly; Max-Age=3600"))
            preview.enqueue(MockResponse().setBody("preview document"))
            client.load(launch).use { assertEquals("preview document", it.body.string()) }
            assertEquals("session=fixture-only", account.takeRequest().getHeader("Cookie"))
            assertFalse(client.allows(launch))
            assertTrue(client.allows(preview.url("/theme.css").toString()))
            preview.enqueue(MockResponse().setBody("preview stylesheet"))
            client.load(preview.url("/theme.css").toString()).use { assertEquals("preview stylesheet", it.body.string()) }
            repeat(2) {
                val request = preview.takeRequest()
                assertEquals("preview=delegated-only", request.getHeader("Cookie"))
                for (name in listOf("Authorization", "X-Team-Id", "Origin")) assertNull(name, request.getHeader(name))
            }
        } }
    }

    @Test fun `an external launch redirect cannot regain account authentication by returning to the API origin`() = runBlocking {
        MockWebServer().use { account -> MockWebServer().use { preview ->
            account.start(); preview.start()
            val api = MemohApi(OkHttpClient(), endpoint(account, true), json, cloudAuth = cloud(account))
            val launch = account.url("/api/v1/browser-sessions/${"b".repeat(32)}/launch").toString()
            val client = api.browserPreview(launch)
            account.enqueue(MockResponse().setResponseCode(302).setHeader("Location", preview.url("/")))
            preview.enqueue(MockResponse().setResponseCode(302).setHeader("Location", account.url("/redirected")))
            account.enqueue(MockResponse().setBody("no authentication"))
            client.load(launch).close()
            account.takeRequest()
            assertNull(account.takeRequest().getHeader("Cookie"))
            account.enqueue(MockResponse().setBody("still no authentication"))
            client.load(account.url("/redirected").toString()).close()
            val subsequent = account.takeRequest()
            for (name in listOf("Cookie", "Authorization", "X-Team-Id", "Origin")) assertNull(name, subsequent.getHeader(name))
        } }
    }

    @Test fun `Cloud HTML launch posts the one time code and retains only delegated website cookies`() = runBlocking {
        MockWebServer().use { account -> MockWebServer().use { preview ->
            account.start(); preview.start()
            val api = MemohApi(OkHttpClient(), endpoint(account, true), json, cloudAuth = cloud(account))
            val launch = account.url("/api/v1/browser-sessions/${"c".repeat(32)}/launch").toString()
            val client = api.browserPreview(launch)
            account.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                .addHeader("Set-Cookie", "session=account-refreshed; Path=/; HttpOnly; Max-Age=3600").setBody("""
                <!doctype html><title>Opening preview</title>
                <form id="" action='${preview.url("/_memoh/bootstrap")}' method='POST'>
                  <input value='fixture+once&amp;only&#x3d;one' name='code' type='hidden'>
                  <button type='submit'>Continue</button>
                </form><script>document.forms[0].submit()</script>
            """.trimIndent()))
            preview.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/nested/index.html")
                .addHeader("Set-Cookie", "preview=delegated-only; Path=/; HttpOnly; Max-Age=3600"))
            preview.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("ready document"))
            client.load(launch).use {
                assertEquals(preview.url("/nested/index.html"), it.request.url)
                assertEquals("ready document", it.body.string())
            }
            assertEquals("session=fixture-only", account.takeRequest().getHeader("Cookie"))
            val bootstrap = preview.takeRequest()
            assertEquals("POST", bootstrap.method)
            assertEquals("/_memoh/bootstrap", bootstrap.path)
            assertEquals("code=fixture%2Bonce%26only%3Done", bootstrap.body.readUtf8())
            assertEquals(endpoint(account).origin, bootstrap.getHeader("Origin"))
            for (name in listOf("Cookie", "Authorization", "X-Team-Id")) assertNull(name, bootstrap.getHeader(name))
            val document = preview.takeRequest()
            assertEquals("GET", document.method)
            assertEquals("preview=delegated-only", document.getHeader("Cookie"))
            assertNull(document.getHeader("Origin"))
            assertFalse(client.allows(launch))
            preview.enqueue(MockResponse().setBody("stylesheet"))
            client.load(preview.url("/nested/theme.css").toString()).close()
            assertEquals("preview=delegated-only", preview.takeRequest().getHeader("Cookie"))
            assertEquals(1, account.requestCount)
        } }
    }

    @Test fun `a bootstrap redirect cannot forward its code to another origin`() = runBlocking {
        MockWebServer().use { account -> MockWebServer().use { preview -> MockWebServer().use { other ->
            account.start(); preview.start(); other.start()
            val api = MemohApi(OkHttpClient(), endpoint(account, true), json, cloudAuth = cloud(account))
            val launch = account.url("/api/v1/browser-sessions/${"d".repeat(32)}/launch").toString()
            account.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("""
                <form method="post" action="${preview.url("/_memoh/bootstrap")}">
                <input type="hidden" name="code" value="fixture-only"></form>
            """))
            preview.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/receive")))
            try { api.browserPreview(launch).load(launch).close(); fail("Must not forward a bootstrap code") }
            catch (_: UserFacingException) { }
            assertEquals(1, preview.requestCount)
            assertEquals(0, other.requestCount)
        } } }
    }

    @Test fun `workspace HTML cannot initiate a native Cloud bootstrap`() = runBlocking {
        MockWebServer().use { account -> MockWebServer().use { preview ->
            account.start(); preview.start()
            val api = MemohApi(OkHttpClient(), endpoint(account, true), json, cloudAuth = cloud(account))
            val page = account.url("/preview/index.html").toString()
            val html = """<form method="post" action="${preview.url("/_memoh/bootstrap")}"><input type="hidden" name="code" value="untrusted"></form>"""
            account.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html))
            api.browserPreview(page).load(page).use { assertEquals(html, it.body.string()) }
            assertEquals(0, preview.requestCount)
        } }
    }

    @Test fun `invalid launch form code fails without posting or invalidating the account`() = runBlocking {
        MockWebServer().use { account -> MockWebServer().use { preview ->
            account.start(); preview.start()
            val cloud = cloud(account)
            val api = MemohApi(OkHttpClient(), endpoint(account, true), json, cloudAuth = cloud)
            val launch = account.url("/api/v1/browser-sessions/${"e".repeat(32)}/launch").toString()
            account.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("""
                <form method="post" action="${preview.url("/_memoh/bootstrap")}"><input type="hidden" name="code" value=""></form>
            """))
            try { api.browserPreview(launch).load(launch).close(); fail("A code is required") }
            catch (_: UserFacingException) { }
            assertEquals(0, preview.requestCount)
            assertTrue(cloud.hasSession)
        } }
    }

    @Test fun `self hosted same origin resources retry a stale bearer once`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var token = "old"
            var expired = false
            val auth = object : AuthProvider {
                override fun currentLease() = AuthLease(token, null, 1)
                override suspend fun refresh(previous: AuthLease): AuthLease { token = "new"; return currentLease() }
                override fun onSessionExpired(generation: Long) { expired = true }
            }
            val api = MemohApi(OkHttpClient(), endpoint(server), json, auth)
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("ok"))
            val page = server.url("/preview/").toString()
            api.browserResource(page, page).use { assertEquals("ok", it.body.string()) }
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
            assertFalse(expired)
        }
    }

    @Test fun `a target website 401 does not clear the Cloud login`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cloud = cloud(server)
            val api = MemohApi(OkHttpClient(), endpoint(server, true), json, cloudAuth = cloud)
            server.enqueue(MockResponse().setResponseCode(401))
            val page = server.url("/preview/").toString()
            api.browserResource(page, page).use { assertEquals(401, it.code) }
            assertTrue(cloud.hasSession)
        }
    }

    @Test fun `preview navigation rejects other ports schemes and embedded credentials`() {
        val page = "https://preview.example:8443/index.html"
        assertTrue(isBrowserPreviewUrl(page, "https://preview.example:8443/nested/file.js"))
        for (target in listOf("https://preview.example/", "http://preview.example:8443/", "https://other.example:8443/", "https://user:pass@preview.example:8443/", "javascript:alert(1)")) {
            assertFalse(target, isBrowserPreviewUrl(page, target))
        }
    }
}
