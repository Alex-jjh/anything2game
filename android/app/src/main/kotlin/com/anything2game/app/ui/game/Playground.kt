package com.anything2game.app.ui.game

import com.anything2game.app.ui.common.problemCode
import com.anything2game.app.ui.common.problemText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anything2game.app.AppDependencies
import com.anything2game.app.data.HomePreferences
import com.anything2game.app.data.content.ContentLibrary
import com.anything2game.app.data.learning.LearningProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.PackManifest
import com.anything2game.contract.pack.PackOrigin
import com.anything2game.engine.game.GameRuntime
import kotlinx.coroutines.CancellationException

internal data class InstalledGame(val manifest: PackManifest, val spec: GameSpec, val origin: PackOrigin, val resume: Boolean, val stars: Int) {
    val version get() = ContentVersion(spec.gameId, spec.gameVersion)
    val entry get() = ReviewedGames.entryFor(spec)
}

/**
 * Opening a game and leaving is not progress: a game can be resumed only when it has moves that did not finish it.
 */
internal fun resumable(spec: GameSpec, checkpoint: GameCheckpoint?) =
    checkpoint != null && checkpoint.actions.isNotEmpty() && !GameRuntime.replay(spec, checkpoint).completed

/**
 * The shelf's order and contents: a bundled game shows only in the app's language (unless it is being resumed); the game in progress comes
 * first, then the one played last, then the reviewed catalogue in its own order, then everything else by title.
 */
internal fun shelf(games: List<InstalledGame>, language: String, lastPlayed: ContentVersion?): List<InstalledGame> =
    games.filter { it.origin != PackOrigin.PRESET || it.spec.language == language || it.resume }
        .sortedWith(compareByDescending<InstalledGame> { it.resume }.thenByDescending { it.version == lastPlayed }
            .thenBy { g -> g.entry?.let { ReviewedGames.catalog.indexOf(it) } ?: Int.MAX_VALUE }.thenBy { it.spec.title })

/** The shelf of games: what is installed, which one is in progress, and the stars earned on each. */
internal class PlaygroundViewModel(private val content: ContentLibrary, private val progress: LearningProgress,
    private val home: HomePreferences) : ViewModel() {
    companion object {
        fun of(deps: AppDependencies) = PlaygroundViewModel(deps.content, deps.learningProgress, deps.homePreferences)
    }
    /** Null while the shelf is being read. */
    var games by mutableStateOf<List<InstalledGame>?>(null); private set
    var failure by mutableStateOf<String?>(null); private set
    private var loading: Job? = null

    fun load(language: String) {
        loading?.cancel()
        games = null
        loading = viewModelScope.launch {
            try {
                val found = content.games()
                val states = progress.gameStates(found.map { ContentVersion(it.spec.gameId, it.spec.gameVersion) })
                val installed = found.map { (manifest, spec, origin) ->
                    val state = states.getValue(ContentVersion(spec.gameId, spec.gameVersion))
                    InstalledGame(manifest, spec, origin, resumable(spec, state.checkpoint), state.stars)
                }
                games = shelf(installed, language, home.lastGame)
                failure = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = problemCode(e) }
        }
    }

    /** The shelf is not on screen: what it showed is out of date by the time it is back, so it is read again. */
    fun leave() { loading?.cancel(); games = null }

    /** A second look from a tile ends in Play: the game's Quick look has been seen. */
    fun markIntroSeen(game: ContentVersion) = home.markIntroSeen(game)
}

/**
 * The Playground: a shelf of illustrated games, each with the idea it teaches and the stars earned, and one button to ask for more.
 * Adding a game is a demonstration: it runs a short staged sequence and then opens the closest game that was prepared in advance.
 */
@Composable
internal fun Playground(deps: AppDependencies, language: String, padding: PaddingValues, onOpen: (ContentVersion) -> Unit) {
    val profile by deps.profiles.active.collectAsStateWithLifecycle()
    val vm = viewModel(key = "playground:${profile?.id}") { PlaygroundViewModel.of(deps) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var looking by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(language, reload) { vm.load(language) }
    DisposableEffect(vm) { onDispose { vm.leave() } }
    val games = vm.games
    Box(Modifier.fillMaxSize()) {
    PlaygroundContent(games, vm.failure, language, padding, adding, { adding = it }, onRetry = { reload++ }, onOpen = onOpen, onQuickLook = { looking = it.spec.gameId })
    // A second look from a tile: Play opens the game, Close comes back to the shelf.
    games?.firstOrNull { it.spec.gameId == looking && it.spec.intro.isNotEmpty() }?.let { g ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            GameIntro(g.spec, replay = true, onPlay = { vm.markIntroSeen(g.version); looking = null; onOpen(g.version) },
                onSkip = { looking = null }, onExit = { looking = null }, onSource = { source = it.evidenceId })
            g.spec.evidenceRefs.firstOrNull { it.evidenceId == source }?.let { ref ->
                com.anything2game.app.ui.space.ActivityEvidence(deps.materials, deps.sources, ref) { source = null }
            }
        }
    }
    }
}

/** The stateless Playground layout, so the tests and screenshots render exactly what the app shows. */
@Composable
internal fun PlaygroundContent(games: List<InstalledGame>?, failure: String?, language: String, padding: PaddingValues, adding: Boolean,
    setAdding: (Boolean) -> Unit, onRetry: () -> Unit, onOpen: (ContentVersion) -> Unit, previewStep: Int? = null, previewTopic: String? = null,
    onQuickLook: (InstalledGame) -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp).testTag("home_PLAYGROUND"),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.nav_playground), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.playground_subtitle), color = A2g.skin.inkMuted)
            games?.firstOrNull { it.resume }?.let {
                PixelButton(stringResource(R.string.games_continue) + " · " + it.spec.title, { onOpen(it.version) },
                    Modifier.fillMaxWidth().testTag("resume_game"), primary = true)
            }
            if (games == null && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let { Text(problemText(it)); OutlinedButton(onRetry) { Text(stringResource(R.string.home_retry)) } }
            games.orEmpty().chunked(2).forEach { pair ->
                // One height per row: the tiles of a pair stretch to the taller one, so the grid has no ragged gaps.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEach { GameTile(it, language, Modifier.weight(1f).fillMaxHeight(), onQuickLook = { g -> onQuickLook(g) }) { onOpen(it.version) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(72.dp))
        }
        PixelButton(stringResource(R.string.playground_add), { setAdding(true) }, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 14.dp).testTag("add_game"),
            primary = true)
        if (adding) AddGameSheet(games.orEmpty().filter { it.origin == PackOrigin.PRESET && it.spec.language == language }, games != null, language,
            onClose = { setAdding(false) }, onOpen = { setAdding(false); onOpen(it) }, previewStep = previewStep, previewTopic = previewTopic)
    }
}

@Composable
internal fun GameTile(card: InstalledGame, language: String, modifier: Modifier, onQuickLook: (InstalledGame) -> Unit = {}, onClick: () -> Unit) {
    val skin = A2g.skin
    val spec = card.spec
    val partition = spec.stages.first().config is PartitionConfig
    val tag = when {
        card.origin == PackOrigin.PRESET && spec.language == language && spec.gameId == "locker-search-$language" -> "game_partition"
        card.origin == PackOrigin.PRESET && spec.language == language && spec.gameId == "workshop-$language" -> "game_allocation"
        else -> "game_${spec.gameId}_${spec.gameVersion}"
    }
    val stars = stringResource(R.string.playground_stars_cd, card.stars)
    PixelPanel(modifier.testTag(tag), padding = PaddingValues(0.dp), onClick = onClick, fillHeight = true) {
        Box {
            GameIllustration(spec, Modifier.fillMaxWidth())
            Row(Modifier.padding(6.dp)) {
                when {
                    card.resume -> PixelTag(stringResource(R.string.playground_continue), fill = skin.action, ink = skin.onAction)
                    card.stars == 0 -> PixelTag(stringResource(R.string.playground_new), fill = skin.card)
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The concept leads (what the game teaches); how it plays is small secondary text.
            card.entry?.let { PixelTag(it.conceptName(language), Modifier.testTag("concept_chip"), fill = if (partition) skin.evidenceSoft else skin.correctSoft, large = true) }
            Text(spec.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = skin.ink, maxLines = 2)
            // What the player will learn, in one line; the way it plays is the Quick look's business.
            spec.learn?.let { Text(it, Modifier.testTag("learn_line"), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted) }
            when (card.origin) {
                PackOrigin.PRESET -> Unit
                PackOrigin.IMPORT -> Text(stringResource(R.string.games_imported), color = skin.inkMuted, style = MaterialTheme.typography.labelMedium)
                PackOrigin.SERVER -> Text(if (card.manifest.fixture) stringResource(R.string.games_fixture, card.manifest.modelId.orEmpty())
                    else card.manifest.modelId?.let { stringResource(R.string.games_generated, it) } ?: stringResource(R.string.games_provider_unknown),
                    color = skin.inkMuted, style = MaterialTheme.typography.labelMedium)
            }
            if (spec.language != language) Text(java.util.Locale.forLanguageTag(spec.language).getDisplayLanguage(LocalConfiguration.current.locales[0]),
                style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                StarsRow(card.stars, size = 22.dp, modifier = Modifier.semantics { contentDescription = stars })
                if (spec.intro.isNotEmpty()) Row(Modifier.heightIn(min = 40.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { onQuickLook(card) }
                    .testTag("quick_look_${spec.gameId}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PixelSprite(Sprites.sparkle, 16.dp)
                    Text(stringResource(R.string.intro_quick_look), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = skin.ink)
                }
            }
        }
    }
}
