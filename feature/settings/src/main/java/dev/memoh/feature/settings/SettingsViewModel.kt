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
