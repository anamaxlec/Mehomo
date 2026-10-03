package dev.memoh.feature.settings

import androidx.lifecycle.ViewModel
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.model.MemohAccent
import dev.memoh.core.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dev.memoh.core.data.SessionRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.memoh.core.model.TeamMembership

data class CloudTeamsState(val loading: Boolean = false, val teams: List<TeamMembership> = emptyList(), val error: String? = null)

/**
 * Appearance preferences.
 *
 * Exposed as hot state because the theme reads them at the very top of the
 * composition, where a cold flow would restart on every recomposition.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val repository: SessionRepository,
) : ViewModel() {

    val session = repository.state
    private val _cloudTeams = MutableStateFlow(CloudTeamsState())
    val cloudTeams = _cloudTeams.asStateFlow()
    private var teamsJob: Job? = null
    private var teamsAccount: String? = null
    fun loadCloudTeams() {
        teamsJob?.cancel()
        val generation = repository.generation
        val account = repository.state.value.account?.accountId
        teamsAccount = account
        teamsJob = viewModelScope.launch {
            _cloudTeams.value = CloudTeamsState(loading = true)
            try {
                val teams = repository.cloudTeams()
                if (generation == repository.generation) _cloudTeams.value = CloudTeamsState(teams = teams)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _cloudTeams.value = CloudTeamsState(error = "加载团队失败，请重试") }
        }
    }
    fun selectCloudTeam(id: String) {
        if (teamsAccount != repository.state.value.account?.accountId) return
        val team = cloudTeams.value.teams.firstOrNull { it.team?.teamId == id }?.team ?: return
        repository.selectCloudTeam(team)
    }
    val backgroundMonitor = settings.backgroundMonitor.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val liveUpdates = settings.liveUpdates.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val notifyReplyDone = settings.notifyOnReplyDone.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val notifyDecisions = settings.notifyOnDecisions.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setBackgroundMonitor(enabled: Boolean) { viewModelScope.launch { settings.setBackgroundMonitor(enabled) } }
    fun setLiveUpdates(enabled: Boolean) { viewModelScope.launch { settings.setLiveUpdates(enabled) } }
    fun setNotifyReplyDone(enabled: Boolean) { viewModelScope.launch { settings.setNotifyOnReplyDone(enabled) } }
    fun setNotifyDecisions(enabled: Boolean) { viewModelScope.launch { settings.setNotifyOnDecisions(enabled) } }
    val floatingSections = settings.floatingSections.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_FLOATING_SECTIONS)
    fun setFloatingSections(sections: List<String>) { viewModelScope.launch { settings.setFloatingSections(sections) } }
    fun setThemeMode(mode: ThemeMode) { viewModelScope.launch { settings.setThemeMode(mode) } }
    fun setAccent(accent: MemohAccent) { viewModelScope.launch { settings.setAccent(accent) } }
    fun signOut() = repository.signOut()

    val themeMode: StateFlow<ThemeMode> = settings.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeMode.System,
    )

    val accent: StateFlow<MemohAccent> = settings.accent.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MemohAccent.Violet,
    )
}
