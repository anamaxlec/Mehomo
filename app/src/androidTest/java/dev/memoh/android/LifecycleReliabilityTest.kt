package dev.memoh.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.*
import dev.memoh.core.model.ChatAttachment
import dev.memoh.core.network.*
import dev.memoh.feature.chat.ChatSocketFactory
import dev.memoh.feature.chat.ChatViewModel
import dev.memoh.feature.login.LoginViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LifecycleReliabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun context(): Context {
        val base = instrumentation.targetContext
        val prefix = "lifecycle-${System.nanoTime()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
    }

    @Test fun loginPersistsTheServerExpiry() = runBlocking {
        ContinuityFixture(json).use { server ->
            val expiry = Instant.ofEpochMilli(System.currentTimeMillis() + 30_000)
            server.httpOverride = { _, target, _ -> when (target.substringBefore('?').removePrefix("/api")) {
                "/ping" -> 200 to """{"status":"ok"}"""
                "/auth/login" -> 200 to """{"access_token":"login-token","user_id":"fixture-user","expires_at":"$expiry"}"""
                else -> null
            } }
            val context = context()
            val credentials = CredentialStore(context, json)
            val cloud = CloudAuth(OkHttpClient(), json)
            val repository = SessionRepository(credentials, json, cloud)
            // Keep the production HTTPS-only URL validation; only this injected fixture transport uses HTTP.
            val fixtureClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertTrue(request.url.isHttps)
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("localhost").build()).build())
            }.build()
            lateinit var vm: LoginViewModel
            instrumentation.runOnMainSync {
                vm = LoginViewModel(credentials, repository, json, fixtureClient, cloud)
                vm.onUrlChange(server.origin.replace("http://localhost", "https://127.0.0.1"))
                vm.onUsernameChange("fixture-user")
                vm.onPasswordChange("fixture-password")
                vm.submitSelfHosted()
            }
            try {
                withTimeout(10_000) { vm.state.first { !it.form.busy } }
                assertTrue(vm.state.value.form.error, repository.state.value.loggedIn)
                val account = repository.state.value.account!!.accountId
                assertEquals(expiry.toEpochMilli(), credentials.expiresAt(account))
                assertEquals(expiry.toEpochMilli(), CredentialStore(context, json).expiresAt(account))
            } finally { instrumentation.runOnMainSync { vm.viewModelScope.cancel() } }
        }
    }

    @Test fun savedServerExpiryRefreshesBeforeSendingAnExpiredRequest() = runBlocking {
        ContinuityFixture(json).use { server ->
            val expiry = Instant.ofEpochMilli(System.currentTimeMillis() + 30_000)
            val renewedExpiry = expiry.plusSeconds(3600)
            val refreshes = AtomicInteger()
            server.httpOverride = { _, target, _ -> if (target.removePrefix("/api") == "/auth/refresh") {
                refreshes.incrementAndGet()
                200 to """{"access_token":"renewed-token","expires_at":"$renewedExpiry"}"""
            } else null }
            val context = context()
            val credentials = CredentialStore(context, json)
            val account = StoredAccount("account", server.origin, "selfhosted")
            credentials.saveAccount(account)
            val repository = SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json))
            repository.saveToken(account.accountId, "login-token", expiry.toString())
            repository.activate(account)
            assertEquals(0, refreshes.get())
            repository.api()!!.bots()
            assertEquals(1, refreshes.get())
            assertEquals("renewed-token", credentials.token(account.accountId))
            assertEquals(renewedExpiry.toEpochMilli(), CredentialStore(context, json).expiresAt(account.accountId))
        }
    }

    @Test fun aRefreshFinishingAfterAnAccountSwitchCannotChangeOrExpireTheNewLogin() = runBlocking {
        ContinuityFixture(json).use { server ->
            val received = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.httpOverride = { _, target, _ -> if (target.removePrefix("/api") == "/auth/refresh") {
                received.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                200 to """{"access_token":"late-refreshed-A","expires_at":"2099-01-01T00:00:00Z"}"""
            } else null }
            val credentials = CredentialStore(context(), json)
            val first = StoredAccount("account-A", server.origin, "selfhosted")
            credentials.saveAccount(first)
            credentials.saveToken(first.accountId, "token-A", null)
            val repository = SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json))
            val previous = repository.currentLease()!!
            val pending = async(Dispatchers.IO) { runCatching { repository.refresh(previous) } }
            try {
                assertTrue(received.await(10, TimeUnit.SECONDS))
                val second = StoredAccount("account-B", "https://unused.example", "selfhosted")
                credentials.saveAccount(second)
                credentials.saveToken(second.accountId, "token-B", null)
                repository.activate(second)
            } finally { release.countDown() }
            assertTrue(pending.await().exceptionOrNull() is CancellationException)
            repository.onSessionExpired(previous.generation)
            assertEquals("token-A", credentials.token(first.accountId))
            assertEquals("token-B", credentials.token("account-B"))
            assertTrue(repository.state.value.loggedIn)
            assertFalse(repository.state.value.sessionExpired)
            assertEquals("account-B", repository.state.value.account!!.accountId)
        }
    }

    @Test fun aTemporaryRefreshServerFailureDoesNotDiscardTheLogin() = runBlocking {
        ContinuityFixture(json).use { server ->
            server.httpOverride = { _, target, _ -> if (target.removePrefix("/api") == "/auth/refresh") 503 to "{}" else null }
            val credentials = CredentialStore(context(), json)
            val account = StoredAccount("account", server.origin, "selfhosted")
            credentials.saveAccount(account)
            credentials.saveToken(account.accountId, "token", System.currentTimeMillis() + 10_000)
            val repository = SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json))
            val failure = runCatching { repository.api()!!.bots() }.exceptionOrNull()
            assertTrue(failure is ApiException && failure.status == 503)
            assertEquals("token", credentials.token(account.accountId))
            assertTrue(repository.state.value.loggedIn)
        }
    }

    @Test fun reauthenticatingTheSameAccountUpdatesIdentityButRemovingAnotherAccountDoesNot() = runBlocking {
        ContinuityFixture(json).use { server ->
            val credentials = CredentialStore(context(), json)
            val account = StoredAccount("account", server.origin, "selfhosted")
            credentials.saveAccount(StoredAccount("other", "https://unused.example", "selfhosted"))
            credentials.saveAccount(account)
            credentials.saveToken(account.accountId, "token", null)
            val repository = SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json))
            val before = repository.state.value
            val oldClient = repository.api()!!
            repository.removeAccount("other")
            assertEquals(before.generation, repository.generation)
            oldClient.bots()
            credentials.saveAccount(account)
            credentials.saveToken(account.accountId, "new-login-token", null)
            assertNull(repository.api())
            repository.activate(account)
            assertNotEquals(before, repository.state.value)
            assertTrue(runCatching { oldClient.bots() }.exceptionOrNull() is CancellationException)
            assertEquals("b", repository.api()!!.bots().single().id)
        }
    }

    @Test fun editingTextLeavesLargeAttachmentFilesUntouchedAndRemovingThemStillPersists() = runBlocking {
        ContinuityFixture(json).use { server ->
            val context = context()
            val credentials = CredentialStore(context, json)
            val account = StoredAccount("account", server.origin, "selfhosted")
            credentials.saveAccount(account)
            credentials.saveToken(account.accountId, "token", null)
            val cloud = CloudAuth(OkHttpClient(), json)
            val repository = SessionRepository(credentials, json, cloud)
            val root = File(context.cacheDir, "attachments")
            val drafts = AttachmentDraftStore(root, json)
            val attachment = ChatAttachment("file", "data:application/octet-stream;base64," +
                Base64.getEncoder().encodeToString(ByteArray(1024 * 1024) { 42 }), "application/octet-stream", "large.bin")
            drafts.save("account", "", "b", "s", listOf(attachment))
            assertEquals(listOf(attachment), drafts.read("account", "", "b", "s"))
            val file = root.walkTopDown().single { it.isFile && it.extension == "json" }
            val untouchedTime = 1_700_000_000_000L
            assertTrue(file.setLastModified(untouchedTime))
            lateinit var vm: ChatViewModel
            instrumentation.runOnMainSync {
                vm = ChatViewModel(repository, json, ChatSocketFactory(OkHttpClient(), json, cloud), SettingsStore(context),
                    ChatHistoryStore(File(context.cacheDir, "history"), json), drafts)
                vm.open("b", "s")
            }
            try {
                withTimeout(10_000) { vm.state.first { !it.draftRestoring } }
                assertEquals(listOf(attachment), vm.state.value.attachments)
                instrumentation.runOnMainSync { repeat(100) { vm.setDraft("正在编辑的文字 $it") } }
                assertEquals(listOf(attachment), drafts.read("account", "", "b", "s"))
                assertEquals(untouchedTime, file.lastModified())
                instrumentation.runOnMainSync { vm.removeAttachment(0) }
                assertTrue(drafts.read("account", "", "b", "s").isEmpty())
            } finally { instrumentation.runOnMainSync { vm.viewModelScope.cancel() } }
        }
    }
}
