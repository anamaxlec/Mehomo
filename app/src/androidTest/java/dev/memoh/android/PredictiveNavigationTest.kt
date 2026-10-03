package dev.memoh.android

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.memoh.core.designsystem.theme.MemohTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PredictiveNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun advance(ms: Long = 1000) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }
    private val coordinates = mutableMapOf<String, LayoutCoordinates>()
    private fun bounds(tag: String): Rect {
        val node = coordinates[tag] ?: return compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Rect(node.localToRoot(Offset.Zero), node.localToRoot(Offset(node.size.width.toFloat(), node.size.height.toFloat())))
    }
    private fun pixel(): Color = compose.onNodeWithTag("viewport").captureToImage().let {
        it.toPixelMap()[it.width / 2, it.height / 2]
    }
    private fun assertRed() {
        val color = pixel()
        assertEquals(1f, color.red, .04f)
        assertEquals(0f, color.green, .04f)
    }
    private fun assertUnveilingTail() {
        var previous = pixel().green
        repeat(36) { frame ->
            advance(16)
            val current = pixel().green
            assertTrue("Returning content flashed back at tail frame $frame: $previous -> $current", current >= previous - .015f)
            previous = current
        }
        assertEquals(1f, previous, .04f)
    }
    private fun progress(value: Float, edge: Int) {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, value, edge)) }
        advance(32)
    }
    private fun start(value: Float, edge: Int) {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge)) }
        // Give the first zero-progress frame time to compose the previous entry.
        progress(0f, edge)
        progress(value, edge)
    }
    @Composable private fun Page(tag: String, color: Color) {
        Box(Modifier.fillMaxSize().testTag(tag).background(color).onGloballyPositioned { coordinates[tag] = it })
    }

    @Test fun ordinaryNavigationUsesFlaresShortFadeAndKeepsTheSpatialTail() {
        lateinit var open: () -> Unit
        lateinit var back: () -> Unit
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme {
            val nav = rememberNavController()
            open = { nav.navigate("detail") }
            back = { nav.popBackStack() }
            MemohNavHost(nav, "parent", Modifier.size(300.dp).testTag("viewport")) {
                memohComposable("parent") { Page("parent", Color.Blue) }
                memohComposable("detail") { Page("detail", Color.Red) }
            }
        } }
        advance()
        compose.runOnIdle(open); advance(32)
        assertEquals("Incoming page stays transparent for the first 50ms", 1f, pixel().blue, .04f)
        advance(80)
        val entering = pixel()
        assertTrue("Incoming page must fade: $entering", entering.red in .1f.. .95f && entering.blue in .1f.. .95f)
        advance(96)
        assertRed()
        assertTrue("Movement continues after the short fade", bounds("detail").left > 1f)
        advance()
        compose.runOnIdle(back); advance(80)
        val leaving = pixel()
        assertTrue("Returning page must fade: $leaving", leaving.red in .05f.. .95f && leaving.blue in .05f.. .95f)
        advance(96)
        assertEquals("Fade finishes well before the spatial transition", 1f, pixel().blue, .04f)
        assertTrue(bounds("parent").left < -1f)
        repeat(36) { frame ->
            advance(16)
            assertEquals("Outgoing page stays transparent throughout tail frame $frame", 1f, pixel().blue, .04f)
        }
        assertEquals(bounds("viewport").left, bounds("parent").left, 1f)
    }

    @Test fun shellFeaturePreviewScalesUnveilsCancelsAndCommitsFromBothEdgesAndDirections() {
        var destination by mutableStateOf(SectionDestination(MainSection.Profile, SectionMotion.None))
        var rtl by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme {
            SectionContent(destination,
                SectionDestination(MainSection.Profile, SectionMotion.Pop).takeIf { destination.section == MainSection.Memory },
                onBack = { destination = SectionDestination(MainSection.Profile, SectionMotion.Pop) },
                modifier = Modifier.size(300.dp).testTag("viewport"), rtl = rtl) { target ->
                Page(target.section.name, if (target.section == MainSection.Memory) Color.Red else Color.White)
            }
        } }
        for (mirrored in listOf(false, true)) for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            compose.runOnIdle { rtl = mirrored; destination = SectionDestination(MainSection.Memory, SectionMotion.Push) }; advance()
            start(.15f, edge)
            val early = bounds("Memory")
            assertTrue("Current page scales down", early.width < bounds("viewport").width * .98f)
            progress(.5f, edge)
            assertTrue("Scale follows gesture progress", bounds("Memory").width < early.width - 1f)
            assertEquals("Bottom page's light scrim unveils with the gesture", .9f, pixel().green, .05f)
            assertEquals(MainSection.Memory, destination.section)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }; advance(32)
            assertTrue("Cancellation rewinds instead of snapping", bounds("Memory").width < bounds("viewport").width * .99f)
            advance()
            assertEquals(MainSection.Memory, destination.section)
            assertEquals(bounds("viewport").width, bounds("Memory").width, 1f)
            assertRed()
            start(.5f, edge)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; advance(32)
            assertTrue("Commit keeps the unveil tail", pixel().green < .99f)
            assertUnveilingTail()
            assertEquals(MainSection.Profile, destination.section)
            assertEquals(bounds("viewport").width, bounds("Profile").width, 1f)
            assertEquals(1f, pixel().green, .04f)
        }
    }

    @Test fun navHostPreviewUsesEdgeGeometryAndThemeScrimThroughCancelAndCommit() {
        lateinit var open: () -> Unit
        lateinit var current: () -> String?
        var rtl by mutableStateOf(false)
        var dark by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            MemohTheme(darkTheme = dark) {
                val nav = rememberNavController()
                open = { nav.navigate("detail") }
                current = { nav.currentDestination?.route }
                MemohNavHost(nav, "parent", Modifier.size(300.dp).testTag("viewport")) {
                    memohComposable("parent") { Page("parent", Color.White) }
                    memohComposable("detail") { Page("detail", Color.Red) }
                }
            }
        } }
        advance()
        for (night in listOf(false, true)) for (mirrored in listOf(false, true)) {
            val edgePositions = mutableMapOf<Int, Float>()
            for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                compose.runOnIdle { rtl = mirrored; dark = night; open() }; advance()
                start(.15f, edge)
                val early = bounds("detail")
                edgePositions[edge] = early.left
                assertTrue("NavHost uses predictive scale rather than ordinary pop", early.width < bounds("viewport").width * .98f)
                progress(.5f, edge)
                assertTrue(bounds("detail").width < early.width - 1f)
                assertEquals(if (night) .6f else .9f, pixel().green, .05f)
                assertEquals("detail", current())
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }; advance(32)
                assertTrue("Cancellation preserves the predictive spec", bounds("detail").width < bounds("viewport").width * .99f)
                advance()
                assertEquals("detail", current())
                assertEquals(bounds("viewport").width, bounds("detail").width, 1f)
                assertRed()
                start(.5f, edge)
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; advance(32)
                assertTrue("NavHost commit retains its scrim until settled", pixel().green < .99f)
                assertUnveilingTail()
                assertEquals("parent", current())
                assertEquals(bounds("viewport").width, bounds("parent").width, 1f)
                assertEquals(1f, pixel().green, .04f)
            }
            assertTrue("Preview geometry depends on the physical swipe edge", edgePositions.getValue(BackEventCompat.EDGE_LEFT) > edgePositions.getValue(BackEventCompat.EDGE_RIGHT) + 2f)
        }
    }

    @Test fun fastCommitInOneFrameStillRunsThePredictiveTail() {
        lateinit var open: () -> Unit
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme {
            val nav = rememberNavController()
            open = { nav.navigate("detail") }
            MemohNavHost(nav, "parent", Modifier.size(300.dp).testTag("viewport")) {
                memohComposable("parent") { Page("parent", Color.White) }
                memohComposable("detail") { Page("detail", Color.Red) }
            }
        } }
        advance()
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            compose.runOnIdle(open); advance()
            compose.runOnIdle {
                val dispatcher = compose.activity.onBackPressedDispatcher
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, .01f, edge))
                dispatcher.onBackPressed()
            }
            advance(80)
            assertTrue("A quick flick must not skip predictive scale", bounds("detail").width < bounds("viewport").width * .98f)
            advance(80)
            assertTrue("Unveiling continues after the outgoing fade finishes", pixel().green in .8f.. .98f)
            assertUnveilingTail()
            assertEquals(1f, pixel().green, .04f)
        }
    }

    @Test fun cancellingBeforeTheFirstProgressEventRestoresTheEntryAndOrdinaryBack() {
        lateinit var open: () -> Unit
        lateinit var back: () -> Unit
        lateinit var lifecycle: () -> Lifecycle.State?
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme {
            val nav = rememberNavController()
            open = { nav.navigate("detail") }
            back = { nav.popBackStack() }
            lifecycle = { nav.currentBackStackEntry?.lifecycle?.currentState }
            MemohNavHost(nav, "parent", Modifier.size(300.dp).testTag("viewport")) {
                memohComposable("parent") { Page("parent", Color.White) }
                memohComposable("detail") { Page("detail", Color.Red) }
            }
        } }
        advance()
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            compose.runOnIdle(open); advance()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge)) }
            advance(32)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }; advance()
            assertEquals("An abandoned gesture must restore the entry lifecycle", Lifecycle.State.RESUMED, lifecycle())
            assertRed()
            compose.runOnIdle(back); advance(80)
            assertEquals("The next button return must use ordinary motion", bounds("viewport").height, bounds("detail").height, 1f)
            advance()
        }
        // Completing a start-only gesture must still animate to the previous page.
        compose.runOnIdle(open); advance()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
        advance(32)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; advance(80)
        assertTrue(bounds("detail").width < bounds("viewport").width * .98f)
        advance()
        assertEquals(Lifecycle.State.RESUMED, lifecycle())
        assertEquals(1f, pixel().green, .04f)
    }

    @Test fun ordinaryShellReturnKeepsTheOutgoingPageTransparentThroughTheTail() {
        var destination by mutableStateOf(SectionDestination(MainSection.Profile, SectionMotion.None))
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme {
            SectionContent(destination, null, onBack = {},
                modifier = Modifier.size(300.dp).testTag("viewport")) { target ->
                Page(target.section.name, if (target.section == MainSection.Memory) Color.Red else Color.White)
            }
        } }
        advance()
        compose.runOnIdle { destination = SectionDestination(MainSection.Memory, SectionMotion.Push) }; advance()
        compose.runOnIdle { destination = SectionDestination(MainSection.Profile, SectionMotion.Pop) }; advance(80)
        assertTrue("Ordinary return still uses Flare's short fade", pixel().green in .05f.. .95f)
        assertUnveilingTail()
        assertEquals(MainSection.Profile, destination.section)
    }

    @Test fun returningDuringEntryDoesNotResetThePagesOpacity() {
        lateinit var open: () -> Unit
        lateinit var back: () -> Unit
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme {
            val nav = rememberNavController()
            open = { nav.navigate("detail") }
            back = { nav.popBackStack() }
            MemohNavHost(nav, "parent", Modifier.size(300.dp).testTag("viewport")) {
                memohComposable("parent") { Page("parent", Color.White) }
                memohComposable("detail") { Page("detail", Color.Red) }
            }
        } }
        advance()
        for (entryTime in listOf(32L, 80L, 112L, 160L)) {
            compose.runOnIdle(open); advance(entryTime)
            compose.runOnIdle(back)
            assertUnveilingTail()
        }
    }

    @Test fun aCancelledGestureFollowedByButtonReturnDoesNotReplayTheFade() {
        lateinit var open: () -> Unit
        lateinit var back: () -> Unit
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme {
            val nav = rememberNavController()
            open = { nav.navigate("detail") }
            back = { nav.popBackStack() }
            MemohNavHost(nav, "parent", Modifier.size(300.dp).testTag("viewport")) {
                memohComposable("parent") { Page("parent", Color.White) }
                memohComposable("detail") { Page("detail", Color.Red) }
            }
        } }
        advance()
        for (delay in listOf(0L, 48L, 1000L)) {
            compose.runOnIdle(open); advance()
            start(.5f, BackEventCompat.EDGE_LEFT)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            if (delay > 0) advance(delay)
            compose.runOnIdle(back)
            assertUnveilingTail()
        }
    }

    @Test fun returningFromAShellPageDuringEntryDoesNotReplayTheEntrance() {
        var destination by mutableStateOf(SectionDestination(MainSection.Profile, SectionMotion.None))
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme {
            SectionContent(destination, null, onBack = {},
                modifier = Modifier.size(300.dp).testTag("viewport")) { target ->
                Page(target.section.name, if (target.section == MainSection.Memory) Color.Red else Color.White)
            }
        } }
        advance()
        for (entryTime in listOf(32L, 80L, 112L, 160L)) {
            compose.runOnIdle { destination = SectionDestination(MainSection.Memory, SectionMotion.Push) }; advance(entryTime)
            compose.runOnIdle { destination = SectionDestination(MainSection.Profile, SectionMotion.Pop) }
            assertUnveilingTail()
        }
    }
}
