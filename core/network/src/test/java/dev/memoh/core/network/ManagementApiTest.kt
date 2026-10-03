package dev.memoh.core.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*

class ManagementApiTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val auth = object : AuthProvider {
        override fun currentLease() = AuthLease("test-token", null, 1)
        override suspend fun refresh(previous: AuthLease) = null
        override fun onSessionExpired() = Unit
    }
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() { server.shutdown() }
    private fun api() = MemohApi(OkHttpClient(), ServerEndpoint(ServerKind.SelfHosted, server.url("/").toString().trimEnd('/'), "", ""), json, auth)
    @Test fun `Cloud profile requests omit team scope without changing later team requests`() = runTest {
        val cloud = CloudAuth(OkHttpClient(), json, ServerEndpoint(ServerKind.OfficialCloud, server.url("/").toString().trimEnd('/'), "", ""))
        cloud.teamId = "team-1"
        server.enqueue(MockResponse().setBody("{}"))
        cloud.platformRead("/users/me", unscoped = true)
        assertNull(server.takeRequest().getHeader("X-Team-Id"))
        server.enqueue(MockResponse().setResponseCode(204))
        cloud.platformWrite("/users/me", "PATCH", apiBody("display_name" to "Name"), unscoped = true)
        val update = server.takeRequest()
        assertNull(update.getHeader("X-Team-Id")); assertEquals("PATCH", update.method)
        server.enqueue(MockResponse().setBody("{}"))
        cloud.platformRead("/teams")
        assertEquals("team-1", server.takeRequest().getHeader("X-Team-Id"))
    }
    @Test fun `Cloud team writes keep cookie Origin and team scope and accept an empty receipt`() = runTest {
        val cloud = CloudAuth(OkHttpClient(), json, ServerEndpoint(ServerKind.OfficialCloud, server.url("/").toString().trimEnd('/'), "", ""))
        cloud.teamId = "workspace-1"
        server.enqueue(MockResponse().setHeader("Set-Cookie", "session=fixture; Path=/").setBody("{}"))
        cloud.platformRead("/fixture"); server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204))
        cloud.platformWrite(managementPath("teams", "team/1", "members", "user"), "PATCH", apiBody("role" to "TEAM_ROLE_ADMIN"))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/teams/team%2F1/members/user", request.path)
        assertEquals("session=fixture", request.getHeader("Cookie"))
        assertEquals("workspace-1", request.getHeader("X-Team-Id"))
        assertEquals(server.url("/").toString().trimEnd('/'), request.getHeader("Origin"))
        assertEquals("TEAM_ROLE_ADMIN", json.parseToJsonElement(request.body.readUtf8()).jsonObject["role"]!!.jsonPrimitive.content)
        server.enqueue(MockResponse().setResponseCode(204))
        cloud.platformWrite("/teams/team/invitations/invite", "DELETE")
        assertEquals("DELETE", server.takeRequest().method)
    }
    @Test fun `speech audio is treated as bytes and shares authenticated JSON request context`() = runTest {
        val audio = byteArrayOf(0, 1, 2, -1, -128)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(audio)))
        val result = api().testSpeech("voice-id", "test", apiBody("speed" to 1.25))
        assertEquals("audio/wav", result.first); assertArrayEquals(audio, result.second)
        val request = server.takeRequest()
        assertEquals("/speech-models/voice-id/test", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals(1.25, json.parseToJsonElement(request.body.readUtf8()).jsonObject["config"]!!.jsonObject["speed"]!!.jsonPrimitive.double, 0.0)
    }
    @Test fun `transcription sends actual multipart audio and config`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"transcribed"}"""))
        assertEquals("transcribed", api().testTranscription("model", "audio.wav", "audio/wav", byteArrayOf(1, 2), apiBody("language" to "zh")).jsonObject["text"]!!.jsonPrimitive.content)
        val request = server.takeRequest()
        assertEquals("/transcription-models/model/test", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\"")); assertTrue(body.contains("\"language\":\"zh\""))
    }
    @Test fun `dependency stream retains terminal receipt and log frames`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"type\":\"log\",\"data\":\"installing\"}\n\ndata: {\"type\":\"done\",\"version\":\"1\"}\n\n"))
        val frames = api().managementStream(listOf("bots", "b", "dependencies", "codex", "install")).toList()
        assertEquals(listOf("log", "done"), frames.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("POST", server.takeRequest().method)
    }
    @Test fun `management path treats names as path segments and empty success bodies are valid`() = runTest {
        assertEquals("/bots/b%2Fother/channel/telegram", managementPath("bots", "b/other", "channel", "telegram"))
        server.enqueue(MockResponse().setResponseCode(204))
        api().managementWrite(listOf("providers", "p"), "DELETE")
        assertEquals("DELETE", server.takeRequest().method)
    }
    @Test fun `backup export returns the real archive and requested sections`() = runTest {
        val bytes = byteArrayOf(80, 75, 3, 4, 1)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(Buffer().write(bytes)))
        assertArrayEquals(bytes, api().exportBotBackup("b", listOf("settings", "history"), ""))
        val request = server.takeRequest()
        assertEquals("/bots/b/backup/export", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(listOf("settings", "history"), body["sections"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(body.containsKey("passphrase"))
    }
    @Test fun `backup preview and import carry the selected restore strategies and target`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("{}")) }
        api().importBotBackup("bot.zip", byteArrayOf(80, 75), "create", null, "", preview = true)
        assertEquals("/bots/backup/import/preview", server.takeRequest().path)
        api().importBotBackup("bot.zip", byteArrayOf(80, 75), "overwrite", "b", "test-passphrase", apiBody("history" to "merge", "models" to "skip"))
        val request = server.takeRequest(); val body = request.body.readUtf8()
        assertEquals("/bots/backup/import", request.path)
        assertTrue(body.contains("name=\"target_bot_id\"")); assertTrue(body.contains("name=\"passphrase\""))
        assertTrue(body.contains("\"history\":\"merge\"")); assertTrue(body.contains("\"models\":\"skip\""))
    }
    @Test fun `workspace removal explicitly preserves data according to the user selection`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        api().deleteWorkspace("b", true)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method); assertEquals("/bots/b/container?preserve_data=true", request.path)
    }
}
