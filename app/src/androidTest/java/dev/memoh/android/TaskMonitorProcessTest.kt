package dev.memoh.android

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.android.notifications.TaskCheckpointStore
import dev.memoh.android.notifications.TaskMonitorService
import dev.memoh.core.data.CredentialStore
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.data.StoredAccount
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Seeds only the isolated audit app. The host then kills its process and checks recovery. */
@RunWith(AndroidJUnit4::class)
class TaskMonitorProcessTest {
    @Test fun prepareRunningTaskForExternalProcessDeath() = runBlocking {
        val origin = InstrumentationRegistry.getArguments().getString("fixture_origin")
        assumeNotNull(origin)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".lifecycleaudit"))
        val json = Json { ignoreUnknownKeys = true }
        val credentials = CredentialStore(context, json)
        val account = StoredAccount("process-recovery", origin!!, "selfhosted")
        credentials.saveAccount(account)
        credentials.saveToken(account.accountId, "fixture-only-token", null)
        val settings = SettingsStore(context)
        settings.setLastBotId("b")
        settings.setBackgroundMonitor(true)
        val checkpoints = TaskCheckpointStore(File(context.noBackupFilesDir, "task-monitor"), json)
        val intent = Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ComponentActivity>(intent).use { scenario ->
            scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, TaskMonitorService::class.java)) }
            withTimeout(15_000) {
                while (checkpoints.read(account.accountId, "b")["s"]?.runId != "run-process") delay(100)
            }
            assertTrue(checkpoints.read(account.accountId, "b").getValue("s").alerted.isEmpty())
        }
    }
}
