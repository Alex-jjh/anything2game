package com.anything2game.app.ui.game

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.ui.home.PaperCard
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.game.GenerationInput

/**
 * The real server generation path (queue, polling, install), kept for the demo controls on the profile tab. The Playground itself
 * never calls the server: its "Add a game" button plays a staged demonstration and opens a pre-generated game.
 */
@Composable
internal fun DeveloperGameTools(deps: AppDependencies, language: String, onOpen: (ContentVersion) -> Unit) {
    var topic by rememberSaveable { mutableStateOf("") }
    val repository = deps.generation ?: return
    GenerationPanel(repository, { it is GenerationInput.ConceptGame }, { busy, enqueue ->
        PaperCard {
            Text(stringResource(R.string.games_prompt), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            OutlinedTextField(topic, { topic = it.take(200) }, Modifier.fillMaxWidth().testTag("concept_input"),
                singleLine = true, label = { Text(stringResource(R.string.games_topic)) })
            Text(stringResource(R.string.games_examples), color = A2g.skin.inkMuted)
            Button({ enqueue { GenerationInput.ConceptGame(topic.trim(), language) } }, Modifier.heightIn(min = 48.dp).testTag("generate_game"),
                enabled = topic.isNotBlank() && !busy) { Text(stringResource(R.string.games_create)) }
        }
    }, onOpen)
}
