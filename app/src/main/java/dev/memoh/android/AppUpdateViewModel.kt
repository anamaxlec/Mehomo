package dev.memoh.android

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.network.AppRelease
import dev.memoh.core.network.GitHubReleases
import dev.memoh.core.network.compareReleaseVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject

enum class UpdateStatus { Idle, Checking, Current, Available, Error }
data class AppUpdateState(val currentVersion: String, val status: UpdateStatus = UpdateStatus.Idle,
    val release: AppRelease? = null, val error: String? = null, val showReminder: Boolean = false)

@HiltViewModel
class AppUpdateViewModel @Inject constructor(@ApplicationContext context: Context, private val settings: SettingsStore,
    http: OkHttpClient, json: Json) : ViewModel() {
    private val releases = GitHubReleases(http, json)
    private val _state = MutableStateFlow(AppUpdateState(context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()))
    val state = _state.asStateFlow()
    val automatic = settings.automaticUpdateChecks.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private var checking = false

    fun check(manual: Boolean = false) {
        if (checking) return
        checking = true
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val last = settings.lastUpdateCheck.first()
                if (!manual && (!settings.automaticUpdateChecks.first() || now >= last && now - last < 24 * 60 * 60 * 1000L)) return@launch
                _state.update { it.copy(status = UpdateStatus.Checking, error = null) }
                settings.recordUpdateCheck(now)
                val release = releases.latest()
                val newer = release != null && (compareReleaseVersions(release.version, state.value.currentVersion) ?: 0) > 0
                val reminder = newer && (manual || settings.notifiedRelease.first() != release?.version)
                _state.update { it.copy(status = if (newer) UpdateStatus.Available else UpdateStatus.Current, release = release,
                    showReminder = reminder, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(status = UpdateStatus.Error, error = if (e is java.io.IOException && e.message?.startsWith("GitHub") == true) e.message else "检查更新失败，请检查网络后重试") } }
            finally { checking = false }
        }
    }

    fun setAutomatic(enabled: Boolean) { viewModelScope.launch { settings.setAutomaticUpdateChecks(enabled); if (enabled) check() } }
    fun showRelease() { if (state.value.status == UpdateStatus.Available) _state.update { it.copy(showReminder = true) } else check(manual = true) }
    fun dismissReminder() {
        _state.update { it.copy(showReminder = false) }
        state.value.release?.let { release -> viewModelScope.launch { settings.markReleaseNotified(release.version) } }
    }
}
