package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.component.LocalFloatingNavigationPadding
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.WorkspaceSurface
import dev.memoh.core.model.Bot
import dev.memoh.feature.bots.ManagementPage
import dev.memoh.feature.sessions.BotFeature
import dev.memoh.feature.sessions.SessionsUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FloatingNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val defaultOrder = listOf(MainSection.Chats, MainSection.Terminal, MainSection.Desktop, MainSection.Profile)
    private var order by mutableStateOf(defaultOrder)
    private var rtl by mutableStateOf(false)
    private var fontScale by mutableFloatStateOf(1f)
    private var sessions by mutableStateOf(SessionsUiState(initialized = true, bot = Bot("bot")))
    private var newSessionClicks = 0
    private lateinit var nav: NavHostController
    private lateinit var navigation: SectionNavigationState
    private lateinit var mainCoordinates: LayoutCoordinates

    private fun advance(ms: Long = 1000) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun setup(settle: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, fontScale)) {
                MemohTheme {
                    nav = rememberNavController()
                    navigation = rememberSectionNavigationState()
                    FloatingNavigationHost(nav, Routes.MAIN, order, navigation, cornerContent = {
                        NewSessionCornerAction(sessions, navigation, { newSessionClicks++ },
                            Modifier.align(androidx.compose.ui.Alignment.CenterEnd))
                    }) {
                        memohComposable(Routes.MAIN) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).onGloballyPositioned { mainCoordinates = it }) {
                                Text("section-${navigation.destination.section.name}")
                            }
                        }
                        memohComposable(Routes.MANAGEMENT, arguments = listOf(navArgument("botId") { type = NavType.StringType }, navArgument("page") { type = NavType.StringType })) {
                            MenuContent()
                        }
                        memohComposable(Routes.HISTORY) { MenuContent() }
                        memohComposable(Routes.SETTINGS, arguments = listOf(navArgument("category") { type = NavType.StringType })) { MenuContent() }
                        memohComposable(Routes.FEATURE, arguments = listOf(navArgument("botId") { type = NavType.StringType }, navArgument("feature") { type = NavType.StringType })) { MenuContent() }
                        memohComposable(Routes.WORKSPACE, arguments = listOf(navArgument("botId") { type = NavType.StringType }, navArgument("surface") { type = NavType.StringType })) { MenuContent() }
                        memohComposable(Routes.CHAT, arguments = listOf(navArgument("botId") { type = NavType.StringType }, navArgument("sessionId") { type = NavType.StringType })) { Text("conversation") }
                        memohComposable(Routes.LOGIN) { Text("login") }
                    }
                }
            }
        }
        if (settle) advance()
    }

    @Composable private fun MenuContent() {
        val reserve = LocalFloatingNavigationPadding.current
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("menu-list"), contentPadding = PaddingValues(bottom = reserve)) {
                items(40) { row -> Text("menu-row-$row", Modifier.fillMaxWidth().height(60.dp).testTag("row-$row")) }
            }
        }
    }

    private fun open(route: String) {
        compose.runOnIdle { nav.navigate(route) }
        advance()
    }

    private fun assertOneBar(selected: MainSection) {
        order.forEach {
            compose.onAllNodesWithContentDescription(it.label).assertCountEquals(1)
            compose.onNodeWithText(it.label).assertIsDisplayed()
        }
        compose.onNodeWithText(selected.label).assertIsSelected()
    }

    @Test fun coldStartShowsBothControlsBeforeTheBotArrivesWithoutASecondEntrance() {
        sessions = SessionsUiState()
        setup(settle = false)
        val bar = compose.onNodeWithContentDescription(MainSection.Chats.label)
        val fab = compose.onNodeWithContentDescription("新建会话")
        advance(48)
        bar.assertIsDisplayed()
        fab.assertIsDisplayed().assertIsNotEnabled().performClick()
        assertEquals(0, newSessionClicks)
        val barEntering = bar.fetchSemanticsNode().boundsInRoot.center
        val fabEntering = fab.fetchSemanticsNode().boundsInRoot.center
        screenshot("floating-controls-cold-start-entering")
        advance()
        val barRest = bar.fetchSemanticsNode().boundsInRoot.center
        val fabRest = fab.fetchSemanticsNode().boundsInRoot.center
        assertTrue("Startup includes intermediate entrance frames", barEntering.y > barRest.y + 1)
        assertEquals("Both controls share the first entrance", barEntering.y - barRest.y, fabEntering.y - fabRest.y, 1f)
        val boundsBeforeReady = fab.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { sessions = SessionsUiState(initialized = true, bot = Bot("bot")) }
        advance(16)
        fab.assertIsEnabled()
        assertEquals("Bot arrival must not start a separate action animation", boundsBeforeReady, fab.fetchSemanticsNode().boundsInRoot)
        assertEquals(barRest, bar.fetchSemanticsNode().boundsInRoot.center)
        screenshot("floating-controls-cold-start-ready")
        fab.performClick()
        assertEquals(1, newSessionClicks)
        compose.runOnIdle { sessions = sessions.copy(creating = true) }
        advance(16)
        fab.assertIsNotEnabled().performClick()
        assertEquals("Repeated taps during creation are ignored", 1, newSessionClicks)
        compose.runOnIdle { sessions = SessionsUiState(initialized = true) }
        advance()
        fab.assertDoesNotExist()
        bar.assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun allRootMenusHaveOneSwitcherAndSelectingATabClearsTheNestedStack() {
        setup()
        val routes = ManagementPage.entries.map { Routes.management("bot", it.name) } + Routes.HISTORY +
            SettingsCategory.entries.map { Routes.settings(it) } +
            BotFeature.entries.map { Routes.feature("bot", it.name) }
        for (route in routes) {
            open(route)
            assertOneBar(MainSection.Profile)
            compose.runOnIdle { nav.popBackStack(Routes.MAIN, false) }; advance()
        }
        for (surface in WorkspaceSurface.entries) {
            open(Routes.workspace("bot", surface.name))
            assertOneBar(MainSection.entries.first { it.name == surface.name }.takeIf { it in order } ?: MainSection.Profile)
            compose.runOnIdle { nav.popBackStack(Routes.MAIN, false) }; advance()
        }
        open(Routes.chat("bot", "session"))
        order.forEach { compose.onNodeWithContentDescription(it.label).assertDoesNotExist() }
        open(Routes.management("bot", ManagementPage.Bot.name))
        open(Routes.management("bot", ManagementPage.Models.name))
        assertOneBar(MainSection.Profile)
        compose.onNodeWithText(MainSection.Chats.label).performClick(); advance()
        assertOneBar(MainSection.Chats)
        compose.runOnIdle {
            assertEquals(Routes.MAIN, nav.currentDestination?.route)
            assertEquals(MainSection.Chats, navigation.destination.section)
            val stack = nav.navigatorProvider.getNavigator<ComposeNavigator>("composable").backStack.value
            assertEquals(listOf(Routes.MAIN), stack.map { it.destination.route })
        }
        open(Routes.LOGIN)
        order.forEach { compose.onNodeWithContentDescription(it.label).assertDoesNotExist() }
    }

    @Test fun menuToTabMotionFollowsCustomOrderAndLayoutDirection() {
        setup()
        val orders = listOf(defaultOrder, listOf(MainSection.Profile, MainSection.Apps, MainSection.Chats, MainSection.Terminal))
        val sources = listOf(Routes.management("bot", ManagementPage.Models.name) to MainSection.Profile,
            Routes.feature("bot", BotFeature.Apps.name) to MainSection.Apps,
            Routes.workspace("bot", WorkspaceSurface.Terminal.name) to MainSection.Terminal)
        for (items in orders) for (mirrored in listOf(false, true)) for ((route, source) in sources) for (target in items) {
            compose.runOnIdle { order = items; rtl = mirrored }
            advance()
            open(route)
            val from = source.takeIf { it in items } ?: MainSection.Profile
            assertOneBar(from)
            compose.onNodeWithText(target.label).performClick()
            advance(80)
            val motion = tabMotion(from, target, items, mirrored)
            if (motion in listOf(SectionMotion.Forward, SectionMotion.Backward)) compose.runOnIdle {
                val x = mainCoordinates.localToRoot(Offset.Zero).x
                assertTrue("$items $source → $target RTL=$mirrored x=$x", if (motion == SectionMotion.Forward) x > 1f else x < -1f)
            }
            advance()
            assertOneBar(target)
            compose.runOnIdle { assertEquals(target, navigation.destination.section); assertEquals(Routes.MAIN, nav.currentDestination?.route) }
        }
    }

    @Test fun lastMenuRowCanScrollAboveTheBarAtLargerFontSizes() {
        setup()
        for (scale in listOf(1f, 1.6f)) {
            compose.runOnIdle { fontScale = scale }
            advance()
            open(Routes.management("bot", ManagementPage.Models.name))
            compose.onNodeWithTag("menu-list").performScrollToIndex(39)
            advance()
            val row = compose.onNodeWithTag("row-39").fetchSemanticsNode().boundsInRoot
            val bar = compose.onNodeWithContentDescription(MainSection.Profile.label).fetchSemanticsNode().boundsInRoot
            assertTrue("Last row is clear of the floating bar at fontScale=$scale: row=$row bar=$bar", row.bottom < bar.top)
            compose.runOnIdle { nav.popBackStack(Routes.MAIN, false) }; advance()
        }
    }

    @Test fun bothFloatingControlsEnterAndLeaveTogetherWhenReturningFromConversation() {
        setup()
        val bar = compose.onNodeWithContentDescription(MainSection.Chats.label)
        val fab = compose.onNodeWithContentDescription("新建会话")
        fun positions() = bar.fetchSemanticsNode().boundsInRoot.center to fab.fetchSemanticsNode().boundsInRoot.center
        for (mirrored in listOf(false, true)) {
            compose.runOnIdle { rtl = mirrored; fontScale = if (mirrored) 1.3f else 1f }
            advance()
            val (barRest, fabRest) = positions()
            compose.runOnIdle { nav.navigate(Routes.chat("bot", "long-session")) }
            advance(80)
            val (barExit, fabExit) = positions()
            assertEquals("Both controls share the exit displacement", barExit.y - barRest.y, fabExit.y - fabRest.y, 1f)
            advance()
            bar.assertDoesNotExist(); fab.assertDoesNotExist()
            compose.runOnIdle { nav.popBackStack() }
            advance(48)
            val (barEnter, fabEnter) = positions()
            assertTrue("The floating controls enter through intermediate frames", barEnter.y > barRest.y + 1)
            assertEquals("Both controls share the entrance displacement", barEnter.y - barRest.y, fabEnter.y - fabRest.y, 1f)
            assertEquals("The switcher stays independent of the page slide", barRest.x, barEnter.x, 1f)
            assertEquals("The corner action stays independent of the page slide", fabRest.x, fabEnter.x, 1f)
            screenshot("floating-controls-return-${if (mirrored) "rtl" else "ltr"}")
            advance()
            assertEquals(barRest, positions().first)
            assertEquals(fabRest, positions().second)
            // A quick return followed by another open must not leave either control behind.
            open(Routes.chat("bot", "again"))
            compose.runOnIdle { nav.popBackStack() }; advance(64)
            compose.runOnIdle { nav.navigate(Routes.chat("bot", "interrupt")) }; advance()
            bar.assertDoesNotExist(); fab.assertDoesNotExist()
            compose.runOnIdle { nav.popBackStack() }; advance()
            assertOneBar(MainSection.Chats)
            fab.assertIsDisplayed()
        }
        screenshot("floating-controls-return-settled")
    }
}
