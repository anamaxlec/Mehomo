package dev.memoh.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    private fun endpoint(input: String): ServerEndpoint {
        val result = ServerUrl.parse(input)
        assertTrue("expected $input to parse, got $result", result is UrlParseResult.Ok)
        return (result as UrlParseResult.Ok).endpoint
    }

    private fun invalid(input: String) {
        assertTrue(
            "expected $input to be rejected, got ${ServerUrl.parse(input)}",
            ServerUrl.parse(input) is UrlParseResult.Invalid,
        )
    }

    @Test
    fun `bare host gets https`() {
        val e = endpoint("memoh.example.com")
        assertEquals("https://memoh.example.com", e.origin)
        assertEquals(ServerKind.SelfHosted, e.kind)
    }

    @Test
    fun `explicit https is preserved`() {
        assertEquals("https://memoh.example.com", endpoint("https://memoh.example.com").origin)
    }

    @Test
    fun `cleartext http is refused rather than silently upgraded`() {
        invalid("http://memoh.example.com")
    }

    @Test
    fun `trailing slash is trimmed`() {
        val e = endpoint("https://memoh.example.com/")
        assertEquals("https://memoh.example.com", e.origin)
        assertEquals("", e.apiPrefix)
        assertEquals("https://memoh.example.com/bots", e.api("/bots"))
    }

    @Test
    fun `an api suffix in the input is kept`() {
        val e = endpoint("https://memoh.example.com/api")
        assertEquals("https://memoh.example.com", e.origin)
        assertEquals("/api", e.apiPrefix)
        assertEquals("https://memoh.example.com/api/bots", e.api("/bots"))
    }

    @Test
    fun `a non-default port is kept and a default one is dropped`() {
        assertEquals("https://memoh.example.com:8080", endpoint("https://memoh.example.com:8080").origin)
        assertEquals("https://memoh.example.com", endpoint("https://memoh.example.com:443").origin)
    }

    @Test
    fun `protocol-relative input gets https`() {
        assertEquals("https://memoh.example.com", endpoint("//memoh.example.com").origin)
    }

    @Test
    fun `blank and hostless input is rejected`() {
        invalid("")
        invalid("   ")
        invalid("localhost")
    }

    @Test
    fun `the official cloud origin is pinned to its fixed prefixes`() {
        val e = endpoint("app.memoh.net")
        assertEquals(ServerKind.OfficialCloud, e.kind)
        assertEquals("https://app.memoh.net", e.origin)
        assertEquals("https://app.memoh.net/api/v1/users/me", e.platform("/users/me"))
        assertEquals(
            "https://app.memoh.net/api/memoh/bots/b1/web/ws",
            e.memoh("/bots/b1/web/ws"),
        )
    }

    @Test
    fun `cloud is never probed for an api suffix`() {
        // Probing would break the platform's routing, so only one candidate.
        val candidates = ServerUrl.candidates("https://app.memoh.net")
        assertEquals(1, candidates.size)
        assertEquals(ServerKind.OfficialCloud, candidates.first().kind)
    }

    @Test
    fun `self-hosted input yields the typed endpoint plus an api probe`() {
        val candidates = ServerUrl.candidates("https://memoh.example.com")
        assertEquals(2, candidates.size)
        assertEquals("", candidates[0].apiPrefix)
        assertEquals("/api", candidates[1].apiPrefix)
    }

    @Test
    fun `an explicit api path is not probed again`() {
        val candidates = ServerUrl.candidates("https://memoh.example.com/api")
        assertEquals(1, candidates.size)
        assertEquals("/api", candidates.first().apiPrefix)
    }

    @Test
    fun `paths are joined without doubling slashes`() {
        val e = endpoint("https://memoh.example.com/api")
        assertEquals("https://memoh.example.com/api/bots/b1/sessions", e.api("/bots/b1/sessions"))
        assertEquals("https://memoh.example.com/api/bots", e.api("bots"))
    }
}
