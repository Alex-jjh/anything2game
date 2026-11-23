package com.anything2game.app

import androidx.compose.material3.Scaffold
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.ui.character.Avatars
import com.anything2game.app.ui.character.LocalAvatar
import com.anything2game.app.ui.AppRoot
import com.anything2game.app.ui.Navigator
import androidx.lifecycle.SavedStateHandle
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

    @Test fun sample_student_practice() = sampleHome(HomeTab.SPACES, "p2_sample_practice_en")
    @Test fun sample_student_profile() = sampleHome(HomeTab.PROFILE, "p3_sample_profile_en")

    private fun sampleHome(tab: HomeTab, name: String) {
        val deps = AndroidAppContainer(app)
        runBlocking { deps.profiles.choose(LocalProfile.SAMPLE) }
        val nav = Navigator(SavedStateHandle())
        nav.selectTab(tab)
        rule.setContent { A2gTheme { AppRoot(deps, nav) } }
        val visible = if (tab == HomeTab.SPACES) app.getString(R.string.spaces_open) else app.getString(R.string.study_record)
        rule.waitUntil(30000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            rule.onAllNodesWithText(visible).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/profile/$name.png")
    }
}
