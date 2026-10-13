package dev.memoh.core.network

import dev.memoh.core.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

class RecentApiTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val auth = object : AuthProvider {
        override fun currentLease() = AuthLease("fixture", null, 1)
        override suspend fun refresh(previous: AuthLease) = null
        override fun onSessionExpired() = Unit
    }
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }
    private fun api() = MemohApi(OkHttpClient(), ServerEndpoint(ServerKind.SelfHosted, server.url("/").toString().trimEnd('/'), "", ""), json, auth)
    @Test fun deletionAccepts202AndFailedRemovalCanBeWithdrawn() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"accepted"}"""))
        api().deleteWorkspace("b", true)
        val deletion = server.takeRequest()
        assertEquals("DELETE", deletion.method)
        assertEquals("/bots/b/container?preserve_data=true", deletion.path)
        server.enqueue(MockResponse().setBody("""{"container_id":"c","removal":{"state":"failed","preserve_data":true,"updated_at":"now"}}"""))
        val state = api().managementRead("bots", "b", "container").jsonObject
        assertEquals("failed", state["removal"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204))
        api().cancelWorkspaceRemoval("b")
        val cancel = server.takeRequest()
        assertEquals("POST", cancel.method)
        assertEquals("/bots/b/container/removal/cancel", cancel.path)
    }
    @Test fun httpAndSseFailuresKeepTheSameProblemCodeAndDiagnostics() = runTest {
        val problem = """{"code":"request.field_required","args":{"field":"pattern"},"request_id":"r","trace_id":"t","detail":"private diagnostic"}"""
        server.enqueue(MockResponse().setResponseCode(422).setBody(problem))
        try { api().managementRead("bots", "b", "schedule"); fail("expected a Problem") }
        catch (e: ApiException) {
            assertEquals("缺少 pattern。", e.message)
            assertEquals("r", e.problem.requestId); assertEquals("t", e.problem.traceId)
            assertEquals(problem, e.body)
        }
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"capability.access_denied","request_id":"sse-r"}"""))
        try { api().managementStream(listOf("bots", "b", "dependencies", "codex", "install")).toList(); fail("expected a Problem") }
        catch (e: ApiException) { assertEquals("sse-r", e.problem.requestId); assertTrue(e.message!!.contains("管理权限")) }
    }
    @Test fun remoteDirectoryPathsAreReturnedByTheTargetAndNeverJoinedLocally() = runTest {
        server.enqueue(MockResponse().setBody("""{"workspace_target_id":"remote-1","path":"C:\\Users\\Alice","directories":[{"name":"工程","path":"C:\\Users\\Alice\\工程"}]}"""))
        val listing = api().workdirDirectories("b", "remote-1")
        assertEquals("C:\\Users\\Alice\\工程", listing.directories.single().path)
        val read = server.takeRequest()
        assertEquals("remote-1", read.requestUrl!!.queryParameter("workspace_target_id"))
        assertNull(read.requestUrl!!.queryParameter("path"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"w","name":"工程","path":"C:\\Users\\Alice\\工程","workspace_target_id":"remote-1"}"""))
        api().createWorkdir("b", "工程", listing.directories.single().path, listing.workspaceTargetId)
        val create = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("remote-1", create["workspace_target_id"]!!.jsonPrimitive.content)
        assertEquals(listing.directories.single().path, create["path"]!!.jsonPrimitive.content)
        server.enqueue(MockResponse().setBody("""{"id":"w","name":"新名"}"""))
        api().renameWorkdir("b", "w", "新名")
        val rename = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("name"), rename.keys)
    }
    @Test fun memoryRecordsQuotaAndLifecycleUseTheDocumentedEndpoints() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[],"total":0}"""))
        api().tokenRecords("b", "2026-10-01", "2026-10-14", sessionType = "memory")
        assertEquals("memory", server.takeRequest().requestUrl!!.queryParameter("session_type"))
        server.enqueue(MockResponse().setBody("""{"limit_reached":false,"windows":[{"used_percent":45,"window_minutes":300}]}"""))
        assertEquals(45.0, api().codexUsage("b", "agent/1").windows.single().usedPercent, .0)
        assertEquals("/bots/b/agents/agent%2F1/codex/usage", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"turns":[],"has_more":true,"limit":200}"""))
        assertTrue(api().contextLifecycle("b", "s", 999).hasMore)
        assertEquals("/bots/b/sessions/s/context-lifecycle?limit=200", server.takeRequest().path)
    }
    @Test fun partialUpdateChecksKeepSuccessfulAndPreviousAppResults() = runTest {
        server.enqueue(MockResponse().setBody("""{"update_checks_incomplete":true,"items":[{"app_id":"a","available_version":"2"},{"app_id":"b","last_checked_at":"before","last_error_code":"app.operation_failed","status":"failed","connectors":[{"type":"drive","status":"linked"}]}]}"""))
        val result = json.decodeFromJsonElement<InstalledApps>(api().checkAppUpdates("b"))
        assertTrue(result.updateChecksIncomplete)
        assertEquals("2", result.items?.first()?.availableVersion)
        assertEquals("before", result.items?.last()?.lastCheckedAt)
        assertEquals("failed", result.items?.last()?.status)
        assertEquals("app.operation_failed", result.items?.last()?.lastErrorCode)
    }
}
