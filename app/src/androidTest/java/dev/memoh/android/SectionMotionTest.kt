package dev.memoh.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.memoh.core.designsystem.theme.MemohTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SectionMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun allOrdersDirectionsAndInterruptedTransitionsMoveAccordingToTheDestination() {
        val orders = listOf(listOf(MainSection.Chats, MainSection.Terminal, MainSection.Desktop, MainSection.Profile),
            listOf(MainSection.Profile, MainSection.Schedules, MainSection.Chats, MainSection.Memory),
            listOf(MainSection.Files, MainSection.Profile, MainSection.Browser, MainSection.Chats))
        var destination by mutableStateOf(SectionDestination(MainSection.Chats, SectionMotion.None))
        val positions = mutableMapOf<MainSection, Float>()
        compose.mainClock.autoAdvance = false
        compose.setContent { MemohTheme {
            SectionContent(destination, backDestination = null, onBack = {}, modifier = Modifier.size(300.dp)) { target ->
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).onGloballyPositioned { positions[target.section] = it.positionInRoot().x }, contentAlignment = Alignment.Center) { Text(target.section.name) }
            }
        } }
        fun advance(time: Long) { compose.mainClock.advanceTimeBy(time); compose.waitForIdle() }
        for (order in orders) for (rtl in listOf(false, true)) for (from in order) for (to in order) {
            if (from == to) continue
            compose.runOnIdle { destination = SectionDestination(from, SectionMotion.None) }; advance(1200)
            compose.runOnIdle { destination = SectionDestination(to, tabMotion(from, to, order, rtl)) }; advance(80)
            compose.runOnIdle {
                val position = requireNotNull(positions[to])
                val expected = tabMotion(from, to, order, rtl)
                assertTrue("$order $from → $to RTL=$rtl x=$position", if (expected == SectionMotion.Forward) position > 1 else position < -1)
            }
            advance(1200)
        }
        val order = orders.first()
        for ((from, to) in listOf(MainSection.Chats to MainSection.Profile, MainSection.Profile to MainSection.Chats)) {
            compose.runOnIdle { destination = SectionDestination(from, SectionMotion.None) }; advance(1200)
            compose.runOnIdle { destination = SectionDestination(to, tabMotion(from, to, order, false)) }; advance(80)
            compose.runOnIdle { destination = SectionDestination(from, tabMotion(to, from, order, false)) }; advance(80)
            compose.runOnIdle {
                val position = requireNotNull(positions[from])
                val expected = tabMotion(to, from, order, false)
                assertTrue("Interrupted $to → $from x=$position", if (expected == SectionMotion.Forward) position > 1 else position < -1)
            }
            advance(1200)
        }
    }
}
