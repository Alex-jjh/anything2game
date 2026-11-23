package com.anything2game.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.anything2game.app.data.learning.*
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.data.tutor.*
import com.anything2game.app.ui.*
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.pack.*
import com.anything2game.contract.space.SpaceMember
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VerifiedMaterialActionsTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun one_verified_document_offers_its_uploaded_tutor_and_compiled_practice_without_the_unrelated_sideload() {
        val base = AndroidAppContainer(RuntimeEnvironment.getApplication())
        runBlocking { base.profiles.choose(LocalProfile.GUEST) }
        val catalog = MaterialCatalog(base.content, base.tutors, authenticated = { it.pkg.packageId == "owned-course" })
        val spaces = SpaceRepository(File(tmp.root, "spaces"), catalog::materials)
        val deps = object : AppDependencies by base {
            override val materials = catalog
            override val spaces = spaces
        }
        val space = runBlocking {
            val pkg = base.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
            val session = base.tutors.open(pkg.packageId).cache.session.copy(sessionId = "tut_verified", mode = "mock")
            base.tutors.save(TutorCache(session))
            for (id in listOf("owned-course", "other-course")) {
                val pack = PackCodec.build(pkg.copy(packageId = id), emptyList(), PackOrigin.SERVER)
                base.content.install(PackCodec.toBytes(pack).inputStream())
            }
            spaces.list().single { it.members == listOf(SpaceMember.ServerDocumentRef(pkg.documentId, pkg.documentVersion)) }
        }
        val nav = Navigator(SavedStateHandle()).apply { openSpace(space.spaceId, true) }
        rule.setContent { A2gTheme { AppRoot(deps, nav) } }
        rule.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            runCatching { rule.onNodeWithTag("space_materials").performScrollToNode(hasTestTag("play_owned-course_L1")); true }.getOrDefault(false)
        }
        rule.onNodeWithTag("play_other-course_L1").assertDoesNotExist()
        rule.onNodeWithTag("space_materials").performScrollToNode(hasText(RuntimeEnvironment.getApplication().getString(R.string.tutor_resume)))
        rule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.tutor_resume)).performClick()
        assertEquals("tut_verified", (nav.route.value as Route.Read).packageId)
    }
}
