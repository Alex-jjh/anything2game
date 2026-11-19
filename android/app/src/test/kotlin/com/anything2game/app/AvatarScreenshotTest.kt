package com.anything2game.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anything2game.app.ui.character.AvatarSignals
import com.anything2game.app.ui.character.Avatars
import com.anything2game.app.ui.character.LocalAvatarSignals
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Expression sheet for every character, and a frame sequence of the live reactions (stitched into a GIF for review). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class AvatarScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun expression_sheet() {
        rule.setContent {
            A2gTheme {
                Column(Modifier.fillMaxSize().background(A2g.skin.paper).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Avatars.all.forEach { a ->
                        Text(stringResource(a.nameRes) + " · " + stringResource(a.traitRes), style = MaterialTheme.typography.titleSmall, color = A2g.skin.ink, fontWeight = FontWeight.Bold)
                        Mood.entries.chunked(5).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                row.forEach { m ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        a.Draw(m, Modifier.size(68.dp))
                                        Text(m.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = A2g.skin.inkMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/avatar/expression_sheet.png")
    }

    /** idle → swipe lean right/left → doubt → hold (swell) → OBJECTION → happy → deflated → celebrate → walking (sleepy). */
    @Test fun live_reactions() {
        val signals = AvatarSignals()
        var mood by mutableStateOf(Mood.IDLE)
        var caption by mutableStateOf("idle")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            A2gTheme {
                CompositionLocalProvider(LocalAvatarSignals provides signals) {
                    Column(Modifier.fillMaxSize().background(A2g.skin.paper).padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(caption, style = MaterialTheme.typography.headlineSmall, color = A2g.skin.ink, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Avatars.all.forEach { a -> Box { a.Draw(mood, Modifier.size(118.dp)) } }
                        }
                    }
                }
            }
        }
        var frame = 0
        fun shoot(ms: Long, n: Int, each: (Int) -> Unit = {}) = repeat(n) { i ->
            each(i); rule.mainClock.advanceTimeBy(ms)
            rule.onRoot().captureRoboImage("build/outputs/roborazzi/avatar/frames/f%03d.png".format(frame++))
        }
        shoot(80, 6)
        caption = "swipe: lean with the card"
        shoot(60, 10) { signals.lean = (it + 1) / 10f }
        shoot(60, 14) { signals.lean = 1f - (it + 1) / 7f }
        shoot(60, 6) { signals.lean = 0f }
        caption = "pick a statement: doubt"; signals.focus = Mood.DOUBT; mood = Mood.DOUBT
        shoot(80, 8)
        caption = "hold to object: swell"; mood = Mood.CHARGING
        shoot(50, 14) { signals.charge = (it + 1) / 14f }
        caption = "OBJECTION!"; signals.charge = 0f; mood = Mood.OBJECT
        shoot(50, 16)
        caption = "correct"; mood = Mood.HAPPY
        shoot(50, 14)
        caption = "wrong"; mood = Mood.DEFLATED
        shoot(70, 10)
        caption = "case closed"; mood = Mood.CELEBRATE
        shoot(60, 18)
        caption = "walking: paused"; mood = Mood.SLEEPY
        shoot(90, 10)
    }
}
