package com.anything2game.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.SavedStateHandle
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.ui.AppRoot
import com.anything2game.app.ui.Navigator
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
import org.robolectric.shadows.ShadowLooper

/** The learning map as a player meets it: Lin's lived-in Spaces, the node sheet, locked feedback, the empty state, large text and landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class SpacesMapScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()

    @Before fun still() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun open(profile: LocalProfile, pkg: String?, fontScale: Float? = null, create: String? = null): AndroidAppContainer {
        val deps = AndroidAppContainer(app)
        val spaceId = runBlocking {
            deps.profiles.choose(profile)
            if (create != null) deps.spaces.create(create).spaceId else {
                val material = deps.materials.materials().single { it.localPack?.packageId == pkg }
                deps.spaces.list().first { material.member in it.members || it.members.any { m -> m.version().contentId == pkg } }.spaceId
            }
        }
        val nav = Navigator(SavedStateHandle()).apply { openSpace(spaceId) }
        rule.setContent { A2gTheme {
            if (fontScale == null) AppRoot(deps, nav)
            else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { AppRoot(deps, nav) }
        } }
        return deps
    }

    /** The tabs are sticky, so they are always there; the map is ready once a node (or the empty state) is drawn. */
    private fun ready(tag: String? = null) {
        rule.waitUntil(30000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodes(hasTestTagPrefix("route_node_") or hasTestTag("route_empty")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) { rule.onRoot().captureRoboImage("build/outputs/roborazzi/spaces/$name.png") }

    @Test fun lin_attention_map_en() { open(LocalProfile.SAMPLE, "demo_attention"); ready(); shot("s1_lin_attention_en") }

    @Test @Config(qualifiers = "zh-w393dp-h852dp-xxhdpi")
    fun lin_zh_map() {
        val deps = open(LocalProfile.SAMPLE, "demo_zh_spaced")
        runBlocking {
            val route = deps.materials.materials().single { it.localPack?.packageId == "demo_zh_spaced" }.nodes
            route.take(2).forEach { deps.learningProgress.recordRead(it.activity as com.anything2game.contract.space.ActivityRef.Tutor, System.currentTimeMillis() - 3_600_000) }
        }
        ready(); shot("s2_zh_map")
    }

    @Test fun lin_resnet_map_continue() { open(LocalProfile.SAMPLE, "demo_resnet"); ready(); shot("s3_lin_resnet_en") }

    @Test @Config(qualifiers = "w393dp-h1900dp-xxhdpi")
    fun tall_attention() { open(LocalProfile.SAMPLE, "demo_attention"); ready(); shot("t1_attention_tall_en") }

    @Test @Config(qualifiers = "zh-w393dp-h1500dp-xxhdpi")
    fun tall_zh() { open(LocalProfile.SAMPLE, "demo_resnet"); ready(); shot("t2_resnet_tall_zh") }

    @Test fun node_sheet() {
        open(LocalProfile.SAMPLE, "demo_attention"); ready()
        rule.onNode(hasTestTagPrefix("route_node_") and hasContentDescription("up next", substring = true)).performClick()
        rule.waitForIdle()
        shot("s4_node_sheet_en")
    }

    @Test @Config(qualifiers = "w393dp-h1000dp-xxhdpi")
    fun locked_node_feedback() {
        open(LocalProfile.SAMPLE, "demo_attention"); ready()
        val locked = hasTestTagPrefix("route_node_") and hasContentDescription("locked", substring = true)
        rule.onNodeWithTag("space_route").performScrollToNode(locked)
        rule.onAllNodes(locked)[0].performClick()
        rule.onNodeWithTag("route_snackbar").assertExists()
        shot("s5_locked_feedback_en")
    }

    @Test fun empty_state() { open(LocalProfile.GUEST, null, create = "Biology notes"); ready(); shot("s6_empty_en") }

    @Test fun font_scale_two() { open(LocalProfile.SAMPLE, "demo_attention", fontScale = 2f); ready(); shot("s7_font2_en") }

    @Test @Config(qualifiers = "w852dp-h393dp-land-xxhdpi")
    fun landscape() { open(LocalProfile.SAMPLE, "demo_attention"); ready(); shot("s8_landscape_en") }

    /** The Spaces tab as a first impression: the app's own language first, other-language Spaces with a language chip, counts that agree. */
    private fun spacesList(profile: LocalProfile) {
        val deps = AndroidAppContainer(app)
        runBlocking { deps.profiles.choose(profile) }
        val nav = Navigator(SavedStateHandle())
        rule.setContent { A2gTheme { AppRoot(deps, nav) } }
        rule.waitUntil(30000) { ShadowLooper.idleMainLooper(); rule.onAllNodes(hasTestTagPrefix("open_space_")).fetchSemanticsNodes().size >= 5 }
        rule.waitForIdle()
    }

    @Test @Config(qualifiers = "w393dp-h1500dp-xxhdpi")
    fun spaces_list_en() { spacesList(LocalProfile.SAMPLE); shot("s9_spaces_list_en") }

    @Test @Config(qualifiers = "zh-w393dp-h1500dp-xxhdpi")
    fun spaces_list_zh() { spacesList(LocalProfile.SAMPLE); shot("s10_spaces_list_zh") }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
