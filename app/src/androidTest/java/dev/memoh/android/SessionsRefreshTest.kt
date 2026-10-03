package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.data.CredentialStore
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.network.CloudAuth
import dev.memoh.feature.sessions.SessionsViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionsRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun replacingBotAndSessionRequestsDoesNotShowCancellationAsAnError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = Json { ignoreUnknownKeys = true }
        val credentials = CredentialStore(context, json)
        Assume.assumeTrue(credentials.activeAccount()?.kind == "cloud")
        val repository = SessionRepository(credentials, json, CloudAuth(OkHttpClient(), json, storage = credentials))
        Assume.assumeTrue(repository.state.value.loggedIn)
        val vm = SessionsViewModel(repository, SettingsStore(context))
        val errors = mutableListOf<String>()
        try {
            compose.runOnIdle {
                vm.viewModelScope.launch { vm.state.collect { it.error?.let(errors::add) } }
                vm.start()
                assertTrue(vm.state.value.loading)
                repeat(10) { vm.refresh() }
            }
            compose.waitUntil(45000) { !vm.state.value.loading }
            compose.runOnIdle {
                assertNull(vm.state.value.error)
                assertNotNull(vm.state.value.bot)
                assertTrue(vm.state.value.initialized)
                repeat(10) { vm.refresh() }
            }
            compose.waitUntil(45000) { !vm.state.value.loading }
            compose.runOnIdle {
                assertNull(vm.state.value.error)
                assertTrue("Cancelled loads must not produce a snackbar: $errors", errors.isEmpty())
            }
        } finally {
            compose.runOnIdle { vm.viewModelScope.cancel() }
        }
    }
}
