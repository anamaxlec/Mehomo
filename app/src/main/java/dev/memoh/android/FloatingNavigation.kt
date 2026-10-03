package dev.memoh.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.memoh.core.designsystem.component.LocalFloatingNavigationPadding
import dev.memoh.core.designsystem.component.MemohSectionBar
import dev.memoh.core.designsystem.component.collapseOnScroll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

@Stable
internal class SectionNavigationState(section: MainSection, motion: SectionMotion, anchor: MainSection) {
    var destination by mutableStateOf(SectionDestination(section, motion))
        private set
    var tabAnchor by mutableStateOf(anchor)
        private set
    var barExpanded by mutableStateOf(true)
    // A tab selected from another root destination starts with its final content,
    // while NavHost owns the single spatial transition back to the shell.
    var contentKey by mutableIntStateOf(0)
        private set

    fun openSection(section: MainSection, motion: SectionMotion) {
        if (section == destination.section) return
        destination = SectionDestination(section, motion)
        barExpanded = true
    }

    fun select(section: MainSection, motion: SectionMotion, fromDetail: Boolean) {
        tabAnchor = section
        if (fromDetail) {
            contentKey++
            destination = SectionDestination(section, SectionMotion.None)
        } else openSection(section, motion)
        barExpanded = true
    }

    companion object {
        val saver = Saver<SectionNavigationState, List<String>>(
            save = { listOf(it.destination.section.name, it.destination.motion.name, it.tabAnchor.name) },
            restore = { SectionNavigationState(MainSection.valueOf(it[0]), SectionMotion.valueOf(it[1]), MainSection.valueOf(it[2])) },
        )
    }
}

@Composable
internal fun rememberSectionNavigationState() = rememberSaveable(saver = SectionNavigationState.saver) {
    SectionNavigationState(MainSection.Chats, SectionMotion.None, MainSection.Chats)
}

internal fun floatingSection(route: String?, feature: String?, surface: String?, section: MainSection): MainSection? = when (route) {
    Routes.MAIN -> section
    Routes.MANAGEMENT, Routes.HISTORY -> MainSection.Profile
    Routes.FEATURE -> MainSection.entries.firstOrNull { it.feature?.name == feature } ?: MainSection.Profile
    Routes.WORKSPACE -> MainSection.entries.firstOrNull { it.name == surface } ?: MainSection.Profile
    else -> null
}

internal data class SectionReturn(val entryId: String, val motion: SectionMotion)

/** One persistent M3E switcher for the shell and all of its management pages. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun FloatingNavigationHost(
    navController: NavHostController,
    startDestination: String,
    sections: List<MainSection>,
    navigation: SectionNavigationState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    builder: NavGraphBuilder.() -> Unit,
) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val section = floatingSection(route, entry?.arguments?.getString("feature"), entry?.arguments?.getString("surface"), navigation.destination.section)
    val selected = section?.takeIf { it in sections }
        ?: MainSection.Profile.takeIf { section != null && it in sections }
        ?: navigation.tabAnchor.takeIf { section != null && it in sections }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val keyboardVisible = WindowInsets.isImeVisible
    val visible = enabled && section != null && !keyboardVisible
    var barHeight by remember { mutableStateOf(56.dp) }
    var sectionReturn by remember { mutableStateOf<SectionReturn?>(null) }
    LaunchedEffect(entry?.id) { navigation.barExpanded = true }
    LaunchedEffect(sectionReturn) {
        if (sectionReturn != null) {
            navController.currentBackStackEntryFlow.flatMapLatest { it.lifecycle.currentStateFlow }
                .first { it == Lifecycle.State.RESUMED }
            sectionReturn = null
        }
    }
    CompositionLocalProvider(LocalFloatingNavigationPadding provides if (visible) barHeight + 32.dp else 0.dp) {
        Box(modifier.fillMaxSize().collapseOnScroll(navigation.barExpanded,
            onExpand = { navigation.barExpanded = true }, onCollapse = { navigation.barExpanded = false })) {
            MemohNavHost(navController, startDestination, sectionReturn = sectionReturn, builder = builder)
            if (visible) MemohSectionBar(
                items = sections.map { it.navItem },
                selectedIndex = sections.indexOf(selected),
                collapsed = !navigation.barExpanded && selected != null,
                onSelect = { index ->
                    val target = sections[index]
                    val from = selected ?: target
                    val fromDetail = route != Routes.MAIN
                    val motion = if (target == from && section != from) SectionMotion.Pop else tabMotion(from, target, sections, rtl)
                    if (fromDetail) entry?.let { sectionReturn = SectionReturn(it.id, motion) }
                    navigation.select(target, motion, fromDetail)
                    if (fromDetail && !navController.popBackStack(Routes.MAIN, false)) {
                        navController.navigate(Routes.MAIN) { popUpTo(navController.graph.id) { inclusive = true } }
                    }
                },
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}
