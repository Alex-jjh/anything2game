package com.anything2game.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.anything2game.app.ui.game.GenerationPanel
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.game.GenerationInput
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationPanelRestoreTest {
    @get:Rule val rule = createComposeRule()

    @Test fun a_restored_space_job_appears_when_its_acknowledged_server_identity_finishes_loading() {
        val app = RuntimeEnvironment.getApplication()
        val deps = AndroidAppContainer(app)
        runBlocking { val known = deps.generation.enqueue(GenerationInput.ConceptGame("Earlier job")); deps.generation.cancel(known.id) }
        val id = runBlocking { val job = deps.generation.enqueue(GenerationInput.SpacePath("remote", 1)); deps.generation.cancel(job.id).id }
        val remote = mutableStateOf<String?>(null)
        rule.setContent { A2gTheme {
            val serverId = remote.value
            Column { GenerationPanel(deps.generation, { it is GenerationInput.ConceptGame || (it is GenerationInput.SpacePath && it.spaceId == serverId) }, { _, _ -> }, {}, scopeKey = serverId) }
        } }
        rule.waitUntil(10000) { ShadowLooper.idleMainLooper(); rule.onAllNodesWithText("Earlier job").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(app.getString(R.string.job_cancelled)).assertCountEquals(1)
        rule.runOnIdle { remote.value = "remote" }
        rule.waitUntil(3000) {
            ShadowLooper.idleMainLooper()
            rule.onAllNodesWithText(app.getString(R.string.job_cancelled)).fetchSemanticsNodes().size == 2
        }
        org.junit.Assert.assertTrue(runBlocking { deps.generation.list().any { it.id == id } })
    }
}
