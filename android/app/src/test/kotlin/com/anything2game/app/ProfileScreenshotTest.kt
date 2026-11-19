package com.anything2game.app

import androidx.compose.material3.Scaffold
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.ui.character.Avatars
import com.anything2game.app.ui.character.LocalAvatar
import com.anything2game.app.ui.home.HomeContent
import com.anything2game.app.ui.home.HomeNavigation
import com.anything2game.app.ui.home.HomeTab
import com.anything2game.app.ui.home.WelcomeScreen
import com.anything2game.app.ui.library.LibraryUiState
import com.anything2game.app.ui.library.libraryEntry
import com.anything2game.app.ui.theme.A2gTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Welcome (who is playing) and the sample student's lived-in home, from real seeded data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ProfileScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()

    @Before fun still() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test fun welcome() {
        rule.setContent { A2gTheme { WelcomeScreen(onChoose = {}) } }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/profile/p1_welcome_en.png")
    }

    @Test fun sample_student_practice() = sampleHome(HomeTab.PRACTICE, "p2_sample_practice_en")
    @Test fun sample_student_profile() = sampleHome(HomeTab.PROFILE, "p3_sample_profile_en")

    private fun sampleHome(tab: HomeTab, name: String) {
        val deps = AndroidAppContainer(app)
        // Same state the library view model builds, from the seeded stores.
        val state = runBlocking {
            deps.profiles.choose(LocalProfile.SAMPLE)
            LibraryUiState(loading = false, entries = deps.packages.list().map { sum ->
                val pkg = deps.packages.load(sum.packageId).getOrThrow()
                val frames = com.anything2game.app.ui.play.framesByIntent.filter { it in deps.frames.available(pkg.packageId) }
                libraryEntry(sum, pkg, deps.sessions.load(sum.packageId), deps.mastery.all(sum.packageId), deps.clock.now())
                    .copy(frames = frames, selectedFrame = com.anything2game.contract.FrameId.CASE.takeIf { it in frames } ?: frames.first())
            })
        }
        val avatarId = deps.avatarPreferences.get() ?: "mochi"
        rule.setContent {
            A2gTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalAvatar provides Avatars.of(avatarId)) {
                    Scaffold(bottomBar = { HomeNavigation(tab) {} }) { padding ->
                        HomeContent(tab, state, avatarId, deps.homePreferences.nickname,
                            onSelectAvatar = {}, onRename = {}, onPickPdf = {}, onRead = {}, onPlay = { _, _, _ -> }, onSelectTab = {}, onRetry = {},
                            padding = padding, onSwitchProfile = {})
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/profile/$name.png")
    }
}
