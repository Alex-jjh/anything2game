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

    @Test fun a_missing_restored_space_offers_retry_and_a_return_to_spaces() {
        val app = RuntimeEnvironment.getApplication()
        val deps = AndroidAppContainer(app)
        awaitIo { deps.profiles.choose(LocalProfile.GUEST) }
        val nav = Navigator(SavedStateHandle()).apply { openSpace("missing") }
        rule.setContent { A2gTheme { AppRoot(deps, nav) } }
        fun waitUntilUnavailable() = rule.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodesWithText(app.getString(R.string.spaces_unavailable)).fetchSemanticsNodes().isNotEmpty()
        }
        waitUntilUnavailable()
        rule.onNodeWithText(app.getString(R.string.home_retry)).performClick()
        waitUntilUnavailable()
        rule.onNodeWithText(app.getString(R.string.action_back)).performClick()
        rule.onNodeWithTag("home_SPACES").assertExists()
    }

    @Test fun an_unreadable_space_store_keeps_the_load_error_and_can_recover_on_retry() {
        val app = RuntimeEnvironment.getApplication()
        val base = AndroidAppContainer(app)
        awaitIo { base.profiles.choose(LocalProfile.GUEST) }
        val store = File(tmp.root, "space-store")
        val spaces = SpaceRepository(store, base.materials::materials)
        val valid = awaitIo { spaces.create("Seminar") }
        val workingCopy = store.readBytes()
        store.writeText("broken")
        val deps = object : AppDependencies by base { override val spaces = spaces }
        val nav = Navigator(SavedStateHandle()).apply { openSpace(valid.spaceId) }
        rule.setContent { A2gTheme { AppRoot(deps, nav) } }
        val retry = app.getString(R.string.home_retry)
        rule.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodesWithText(retry).fetchSemanticsNodes().isNotEmpty()
        }
        store.writeBytes(workingCopy)
        rule.onNodeWithText(retry).performClick()
        rule.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodesWithText("Seminar").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("show_materials").assertIsEnabled()
    }

    @Test fun one_verified_document_offers_its_uploaded_tutor_and_compiled_practice_without_the_unrelated_sideload() {
        val base = AndroidAppContainer(RuntimeEnvironment.getApplication())
        awaitIo { base.profiles.choose(LocalProfile.GUEST) }
        val catalog = MaterialCatalog(base.content, base.tutors, authenticated = { it.pkg.packageId == "owned-course" })
        val spaces = SpaceRepository(File(tmp.root, "spaces"), catalog::materials)
        val deps = object : AppDependencies by base {
            override val materials = catalog
            override val spaces = spaces
        }
        val space = awaitIo {
            val pkg = base.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
            val session = base.tutors.open(pkg.packageId).cache.session.copy(sessionId = "tut_verified", mode = "mock")
            base.tutors.save(TutorCache(session))
            for (id in listOf("owned-course", "other-course")) {
                val pack = PackCodec.build(pkg.copy(packageId = id), emptyList(), PackOrigin.SERVER)
                base.content.install(PackCodec.toBytes(pack).inputStream())
            }
            spaces.list().single { it.members == listOf(SpaceMember.ServerDocumentRef(pkg.documentId, pkg.documentVersion)) }
        }
        val documentId = awaitIo { base.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow().documentId }
        val nav = Navigator(SavedStateHandle()).apply { openSpace(space.spaceId, true) }
        rule.setContent { A2gTheme { AppRoot(deps, nav) } }
        // One row for the document; its menu holds both the lesson and the compiled course's practice.
        rule.waitUntil(15000) { ShadowLooper.idleMainLooper(); rule.onAllNodesWithTag("material_more_$documentId").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("material_more_$documentId").performClick()
        rule.onNodeWithTag("practise_$documentId").performClick()
        rule.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            runCatching { rule.onNodeWithTag("space_materials").performScrollToNode(hasTestTag("play_owned-course_L1")); true }.getOrDefault(false)
        }
        rule.onNodeWithTag("play_other-course_L1").assertDoesNotExist()
        rule.onNodeWithTag("space_materials").performScrollToNode(hasTestTag("material_more_$documentId"))
        rule.onNodeWithTag("material_more_$documentId").performClick()
        rule.onNodeWithTag("start_explanation_tut_verified").performClick()
        assertEquals("tut_verified", (nav.route.value as Route.Read).packageId)
    }

    @Test fun materials_allows_switching_from_rookie_back_to_classic_with_overlay_only_manifest() {
        val deps = AndroidAppContainer(RuntimeEnvironment.getApplication())
        val space = awaitIo {
            deps.profiles.choose(LocalProfile.GUEST)
            val pack = deps.materials.materials().single { it.localPack?.packageId == "demo_attention" }.localPack!!
            assertFalse(com.anything2game.contract.FrameId.CLASSIC in deps.content.manifests().single { it.packageId == pack.packageId }.frames)
            deps.spaces.list().single { pack in it.members }
        }
        val nav = Navigator(SavedStateHandle()).apply { openSpace(space.spaceId, true) }
        rule.setContent { A2gTheme { AppRoot(deps, nav) } }
        val list = rule.onNodeWithTag("space_materials")
        rule.waitUntil(15000) { ShadowLooper.idleMainLooper(); rule.onAllNodesWithTag("material_more_demo_attention").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("material_more_demo_attention").performClick()
        rule.onNodeWithTag("practise_demo_attention").performClick()
        rule.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            runCatching { list.performScrollToNode(hasTestTag("play_as_ROOKIE")); true }.getOrDefault(false)
        }
        rule.onNodeWithTag("play_as_ROOKIE").performClick().assertIsSelected()
        list.performScrollToNode(hasTestTag("play_as_CLASSIC"))
        rule.onNodeWithTag("play_as_CLASSIC").performClick().assertIsSelected()
    }

    @Test fun a_local_lesson_citation_names_its_own_material_when_an_uploaded_tutor_uses_the_same_document_labels() {
        val deps = AndroidAppContainer(RuntimeEnvironment.getApplication())
        val (space, material) = awaitIo {
            deps.profiles.choose(LocalProfile.GUEST)
            val pkg = deps.packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
            val other = deps.tutors.open(pkg.packageId).cache.session.copy(sessionId = "tut_other", mode = "mock", title = "Separate uploaded lesson")
            deps.tutors.save(TutorCache(other))
            val m = deps.materials.materials().single { it.localPack?.packageId == pkg.packageId }
            deps.spaces.list().single { m.member in it.members } to m
        }
        val node = material.nodes.first { it.activity is com.anything2game.contract.space.ActivityRef.Tutor }
        val source = node.evidence.single() as com.anything2game.contract.game.EvidenceRef.Pdf
        awaitIo {
            val store = SourceDocumentStore(File(RuntimeEnvironment.getApplication().filesDir, "source-documents"))
            store.save(source.documentId, source.documentVersion, 1,
                com.anything2game.contract.DocumentLayout(listOf(com.anything2game.contract.PageBox(0, 200f, 400f))), "%PDF-separate-upload".toByteArray())
            suspend fun original(target: SourceTarget.Pdf) = deps.sources.open(target.key, com.anything2game.app.data.source.SourceText("t", target.chunks)).original
            assertNotNull((deps.materials.source(source) as SourceTarget.Pdf).let { original(it) })
            assertNull("a lesson's own citation never borrows the upload", (deps.materials.source(source, material) as SourceTarget.Pdf).let { original(it) })
        }
        val label = RuntimeEnvironment.getApplication().getString(R.string.spaces_source_page, material.title,
            source.pageLabel ?: (source.pageIndex + 1).toString())
        val nav = Navigator(SavedStateHandle()).apply { openSpace(space.spaceId) }
        rule.setContent { A2gTheme { AppRoot(deps, nav) } }
        val route = rule.onNodeWithTag("space_route")
        rule.waitUntil(15000) {
            ShadowLooper.idleMainLooper()
            runCatching { route.performScrollToNode(hasTestTag("route_node_${node.nodeId}")); true }.getOrDefault(false)
        }
        rule.onNodeWithTag("route_node_${node.nodeId}").performClick()
        rule.waitUntil(15000) { ShadowLooper.idleMainLooper(); rule.onAllNodesWithTag("route_sheet_source").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasTestTag("route_sheet_source") and hasText(label, substring = true)).assertExists()
    }
}
