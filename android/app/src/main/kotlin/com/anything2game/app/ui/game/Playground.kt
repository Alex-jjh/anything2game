package com.anything2game.app.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.ui.home.PaperCard
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.*
import com.anything2game.engine.game.GameRuntime
import kotlinx.coroutines.CancellationException

private data class InstalledGame(val manifest: PackManifest, val spec: GameSpec, val origin: PackOrigin, val resume: Boolean)
@Composable
internal fun Playground(deps: AppDependencies, language: String, padding: PaddingValues, onOpen: (ContentVersion) -> Unit) {
    var games by remember { mutableStateOf<List<InstalledGame>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var topic by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(language, reload) {
        try {
            games = requireNotNull(deps.content).games().map { (manifest, spec, origin) ->
                val ck = deps.learningProgress?.checkpoint(ContentVersion(spec.gameId, spec.gameVersion))
                InstalledGame(manifest, spec, origin, ck != null && !GameRuntime.replay(spec, ck).completed)
            }.filter { it.origin != PackOrigin.PRESET || it.spec.language == language || it.resume }
                .sortedWith(compareByDescending<InstalledGame> { it.resume }.thenByDescending {
                ContentVersion(it.spec.gameId, it.spec.gameVersion) == deps.homePreferences.lastGame
            }.thenBy { it.spec.title })
            failure = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message }
    }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp).testTag("home_PLAYGROUND"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.nav_playground), style = MaterialTheme.typography.headlineMedium)
        games?.firstOrNull { it.resume }?.let {
            Button({ onOpen(ContentVersion(it.spec.gameId, it.spec.gameVersion)) }, Modifier.fillMaxWidth().testTag("resume_game")) {
                Text(stringResource(R.string.games_continue) + " · " + it.spec.title)
            }
        }
        GenerationPanel(requireNotNull(deps.generation), { it is GenerationInput.ConceptGame }, { busy, enqueue ->
            PaperCard {
                Text(stringResource(R.string.games_prompt), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(topic, { topic = it.take(200) }, Modifier.fillMaxWidth().testTag("concept_input"),
                    singleLine = true, label = { Text(stringResource(R.string.games_topic)) })
                Text(stringResource(R.string.games_examples), color = A2g.skin.inkMuted)
                Button({ enqueue { GenerationInput.ConceptGame(topic.trim(), language) } }, Modifier.heightIn(min = 48.dp).testTag("generate_game"),
                    enabled = topic.isNotBlank() && !busy) { Text(stringResource(R.string.games_create)) }
            }
        }, onOpen)
        Text(stringResource(R.string.games_installed), style = MaterialTheme.typography.titleLarge)
        if (games == null && failure == null) LinearProgressIndicator()
        failure?.let { Text(it); OutlinedButton({ reload++ }) { Text(stringResource(R.string.home_retry)) } }
        games.orEmpty().forEach { card ->
            val spec = card.spec
            val partition = spec.stages.first().config is PartitionConfig
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(54.dp).background(if (partition) A2g.skin.evidenceSoft else A2g.skin.correctSoft,
                        RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Icon(if (partition) Icons.Outlined.GridView else Icons.Outlined.Construction, null, tint = A2g.skin.action)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(spec.title, style = MaterialTheme.typography.titleLarge)
                        Text(when (card.origin) {
                            PackOrigin.PRESET -> stringResource(R.string.badge_pre_generated)
                            PackOrigin.IMPORT -> stringResource(R.string.games_imported)
                            PackOrigin.SERVER -> if (card.manifest.fixture) stringResource(R.string.games_fixture, card.manifest.modelId.orEmpty())
                                else card.manifest.modelId?.let { stringResource(R.string.games_generated, it) } ?: stringResource(R.string.games_provider_unknown)
                        },
                            color = A2g.skin.inkMuted, style = MaterialTheme.typography.labelMedium)
                        if (spec.language != language) Text(java.util.Locale.forLanguageTag(spec.language)
                            .getDisplayLanguage(LocalConfiguration.current.locales[0]), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(spec.stages.first().goal)
                val tag = if (card.origin != PackOrigin.PRESET || spec.language != language) "game_${spec.gameId}_${spec.gameVersion}"
                    else if (partition) "game_partition" else "game_allocation"
                OutlinedButton({ onOpen(ContentVersion(spec.gameId, spec.gameVersion)) },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(stringResource(if (card.resume) R.string.games_continue else R.string.games_ready)) }
            }
        }
    }
}
