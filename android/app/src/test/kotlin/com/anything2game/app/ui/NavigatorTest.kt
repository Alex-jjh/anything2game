package com.anything2game.app.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.ui.home.HomeTab
import com.anything2game.contract.FrameId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigatorTest {
    private fun recreated(handle: SavedStateHandle) = Navigator(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))

    @Test fun `first launch opens reading and saved tab overrides device default`() {
        val handle = SavedStateHandle()
        val nav = Navigator(handle)
        assertEquals(HomeTab.SPACES, nav.tab.value)
        nav.initializeTab("PROFILE")
        nav.selectTab(HomeTab.PLAYGROUND)
        val restored = recreated(handle)
        restored.initializeTab("PROFILE")
        assertEquals(HomeTab.PLAYGROUND, restored.tab.value)
        assertEquals(Route.Home, restored.route.value)
    }
    @Test fun `invalid old tab falls back safely`() {
        val nav = Navigator(SavedStateHandle(mapOf("home.tab" to "removed")))
        assertEquals(HomeTab.SPACES, nav.tab.value)
    }
    @Test fun `legacy saved play route retains its frame`() {
        val nav = Navigator(SavedStateHandle(mapOf("route.pkg" to "p", "route.level" to "L1", "route.frame" to "CASE")))
        assertEquals(Route.Play("p", "L1", FrameId.CASE), nav.route.value)
    }
    @Test fun `reading restores with its originating tab and back clears detail`() {
        val saved = SavedStateHandle()
        val nav = Navigator(saved)
        nav.selectTab(HomeTab.PLAYGROUND)
        nav.openRead("p")
        val restored = recreated(saved)
        assertEquals(Route.Read("p"), restored.route.value)
        restored.toHome()
        assertEquals(HomeTab.PLAYGROUND, restored.tab.value)
        assertEquals(Route.Home, restored.route.value)
    }
    @Test fun `pinned game inside a Space restores and returns to that parent`() {
        val saved = SavedStateHandle()
        val nav = Navigator(saved)
        nav.openSpace("science")
        nav.openGame("locker-en", "v1")
        val restored = recreated(saved)
        assertEquals(Route.Game("locker-en", "v1"), restored.route.value)
        restored.back()
        assertEquals(Route.Space("science"), restored.route.value)
        restored.back()
        assertEquals(Route.Home, restored.route.value)
    }
    @Test fun `a confirmed share restored before acknowledgment reuses its destination and batch identity`() {
        val saved = SavedStateHandle()
        val nav = Navigator(saved)
        val pdf = ImportRequest.PdfFile("paper.pdf", Uri.parse("content://test/paper"), 125L)
        nav.openImport(pdf)
        nav.openSpaceForPdf("reading-group", pdf)
        val pending = nav.route.value as Route.Space
        val batch = requireNotNull(pending.batchId)
        val restored = recreated(saved)
        assertEquals(Route.Space("reading-group", true, pdf, batch), restored.route.value)
        restored.clearPdfConfirmation("some-other-selection")
        assertEquals(pending, restored.route.value)
        restored.clearPdfConfirmation(batch)
        assertEquals(Route.Space("reading-group", true), restored.route.value)
    }
    @Test fun `the full reader restores the selected older package and returns to its materials`() {
        val saved = SavedStateHandle()
        val nav = Navigator(saved)
        nav.openSpace("seminar", true)
        nav.openSource("physics", "2024")
        val restored = recreated(saved)
        assertEquals(Route.Source("physics", "2024"), restored.route.value)
        restored.back()
        assertEquals(Route.Space("seminar", true), restored.route.value)
    }
    @Test fun `import replaces stale game route without saving file contents`() {
        val saved = SavedStateHandle()
        val nav = Navigator(saved)
        nav.openPlay("p", "L1", FrameId.CASE)
        val pdf = ImportRequest.PdfFile("paper.pdf", Uri.parse("content://test/paper"), 123L)
        nav.openImport(pdf)
        assertEquals(Route.Import(pdf), recreated(saved).route.value)
        nav.toHome()
        assertEquals(Route.Home, recreated(saved).route.value)
        assertFalse(saved.contains("route.pkg"))
    }
    @Test fun `large shared text does not enter saved bundle or resurrect old play route`() {
        val saved = SavedStateHandle()
        val nav = Navigator(saved)
        nav.openPlay("p", "L1", FrameId.CLASSIC)
        nav.openImport(ImportRequest.SharedText("Shared", "x".repeat(100000)))
        assertTrue(nav.route.value is Route.Import)
        assertEquals(Route.Home, recreated(saved).route.value)
        assertTrue(saved.keys().none { it.startsWith("route.") })
    }
}
