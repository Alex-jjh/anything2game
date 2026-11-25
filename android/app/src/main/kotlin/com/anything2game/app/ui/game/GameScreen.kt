package com.anything2game.app.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.data.content.ContentLibrary
import com.anything2game.app.data.learning.LearningProgress
import com.anything2game.app.ui.character.LocalAvatar
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.character.rememberReducedMotion
import com.anything2game.app.ui.common.Stamp
import com.anything2game.app.ui.common.StampKind
import com.anything2game.app.ui.space.ActivityEvidence
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.PackOrigin
import com.anything2game.engine.game.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.random.Random

internal class GameViewModel(private val content: ContentLibrary, private val progress: LearningProgress,
    private val version: ContentVersion) : ViewModel() {
    var run by mutableStateOf<GameRun?>(null); private set
    var origin by mutableStateOf<PackOrigin?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    init { load() }
    fun load(replay: Boolean = false) = save {
        val spec = requireNotNull(content.game(version))
        origin = content.originOf(version)
        val checkpoint = if (replay) null else progress.checkpoint(version)
        if (checkpoint == null) progress.start(spec, Random.nextLong()) else GameRuntime.replay(spec, checkpoint)
    }
    fun move(expected: GameRun, action: GameAction) = save { progress.apply(expected.spec, expected.checkpoint, action) }
    private fun save(operation: suspend () -> GameRun) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { error = null; run = operation() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            finally { busy = false }
        }
    }
}

@Composable
internal fun GameScreen(deps: AppDependencies, version: ContentVersion, onBack: () -> Unit, backTo: String? = null) {
    val profile by deps.profiles.active.collectAsStateWithLifecycle()
    val vm = viewModel(key = "game:${profile?.id}:$version") {
        GameViewModel(requireNotNull(deps.content), requireNotNull(deps.learningProgress), version)
    }
    BackHandler { if (!vm.busy) onBack() }
    val run = vm.run
    LaunchedEffect(run?.spec, version) { if (run != null) deps.homePreferences.lastGame = version }
    // Stars are kept per profile the moment a game is completed, so starting a fresh attempt never loses them.
    LaunchedEffect(run?.checkpoint) { run?.let { r -> GameScore.stars(r)?.let { deps.homePreferences.recordStars(version, it) } } }
    var evidence by rememberSaveable { mutableStateOf<String?>(null) }
    // The Quick look opens by itself the first time a game is opened (per profile), and again whenever the player asks for it.
    var introDone by rememberSaveable(version) { mutableStateOf(false) }
    var introAgain by rememberSaveable(version) { mutableStateOf(false) }
    val showIntro = run != null && run.spec.intro.isNotEmpty() && (introAgain || (!introDone && !deps.homePreferences.introSeen(version)))
    if (showIntro && run != null) {
        val finish = { deps.homePreferences.markIntroSeen(version); introDone = true; introAgain = false }
        GameIntro(run.spec, replay = introAgain, onPlay = finish, onSkip = finish, onExit = { if (introAgain) introAgain = false else onBack() },
            onSource = { evidence = it.evidenceId })
        run.spec.evidenceRefs.firstOrNull { it.evidenceId == evidence }?.let { ref -> ActivityEvidence(requireNotNull(deps.materials), ref) { evidence = null } }
        return
    }
    Scaffold(containerColor = A2g.skin.paper) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp).testTag("game_board"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onBack, enabled = !vm.busy) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_back))
            }
            if (vm.busy && run == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let {
                Text(if (run == null) stringResource(R.string.activity_missing) + " " + it else stringResource(R.string.games_save_failed, it), color = A2g.skin.wrong)
                OutlinedButton({ vm.load() }) { Text(stringResource(R.string.home_retry)) }
            }
            if (run != null) {
                GameBody(run, vm.origin, vm.busy, deps.homePreferences.bestStars(version), onMove = { vm.move(run, it) }, onRestart = { vm.load(true) },
                    backTo = backTo, onBackTo = onBack, onQuickLook = if (run.spec.intro.isEmpty()) null else ({ introAgain = true })) {
                    run.spec.evidenceRefs.filter { it.evidenceId in run.stage.evidenceIds }.forEach { ref ->
                        PixelButton(stringResource(R.string.source_open), { evidence = ref.evidenceId })
                        if (evidence == ref.evidenceId) ActivityEvidence(requireNotNull(deps.materials), ref) { evidence = null }
                    }
                }
            }
        }
    }
}

/** Everything under the Back button, stateless so the same layout is what the tests and the screenshots render. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GameBody(run: GameRun, origin: PackOrigin?, busy: Boolean, bestStars: Int, onMove: (GameAction) -> Unit, onRestart: () -> Unit,
    backTo: String? = null, onBackTo: () -> Unit = {}, onQuickLook: (() -> Unit)? = null, source: @Composable () -> Unit) {
    val skin = A2g.skin
    val playing = !busy && !run.actionLimitReached && run.status == StageStatus.PLAYING
    val stageScores = remember(run.checkpoint) { GameScore.stages(run.spec, run.checkpoint) }
    val stars = if (run.completed) GameScore.stars(run) else GameScore.currentStageStars(run)
    val lastAction = run.checkpoint.actions.lastOrNull()
    val mood = when {
        run.status == StageStatus.WON -> if (stars == 3) Mood.CELEBRATE else Mood.HAPPY
        run.status == StageStatus.LOST -> Mood.DEFLATED
        lastAction == GameAction.Help -> Mood.THINKING
        else -> Mood.IDLE
    }
    Column(Modifier.testTag("game_body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(run.spec.title, style = MaterialTheme.typography.headlineMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReviewedGames.catalog.firstOrNull { run.spec.gameId.startsWith(it.key + "-") }?.let {
                PixelTag(it.conceptName(run.spec.language), Modifier.testTag("concept_chip"), fill = skin.card)
            }
            if (origin == PackOrigin.PRESET) PixelTag(stringResource(R.string.games_demo_badge), fill = skin.card, ink = skin.inkMuted,
                modifier = Modifier.testTag("demo_badge"))
        }
        if (onQuickLook != null) PixelButton(stringResource(R.string.intro_quick_look), onQuickLook, Modifier.testTag("quick_look"),
            leading = { PixelSprite(Sprites.sparkle, 20.dp) })
        LevelStrip(run, stageScores)
        if (run.stage.transfer && lastAction == GameAction.Advance) NewConditions(run)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            LocalAvatar.current.Draw(mood, Modifier.size(68.dp).testTag("game_avatar"), pokeable = false)
            PixelPanel(Modifier.weight(1f), fill = skin.card) {
                Text(run.stage.goal, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = skin.ink)
            }
        }
        when (val b = run.board) {
            is PartitionBoard -> PartitionBoardView(b, run.stage.config as PartitionConfig, run.status, playing, onMove)
            is AllocationBoard -> AllocationBoardView(b, run.stage.config as AllocationConfig, lastAction, run.status, playing, onMove)
        }
        if (lastAction == GameAction.Help) PixelPanel(Modifier.fillMaxWidth(), fill = skin.hintSoft) {
            val token = (run.stage.config as? AllocationConfig)?.look()?.token.orEmpty()
            Text(if (run.board is PartitionBoard) stringResource(R.string.games_split_hint) else stringResource(R.string.games_token_hint, token), color = skin.ink)
        }
        if (run.status == StageStatus.PLAYING && !run.actionLimitReached) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PixelButton(stringResource(R.string.games_help), { onMove(GameAction.Help) }, enabled = playing)
            PixelButton(stringResource(R.string.games_reset), { onMove(GameAction.Reset) }, enabled = !busy && run.checkpoint.actions.isNotEmpty())
        }
        if (run.status != StageStatus.PLAYING) Result(run, busy, stars, bestStars, mood, onMove, onRestart, backTo, onBackTo)
        else if (run.actionLimitReached) {
            Text(stringResource(R.string.games_limit), color = skin.inkMuted)
            PixelButton(stringResource(R.string.games_replay), onRestart, Modifier.testTag("restart_game"), enabled = !busy, primary = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = skin.inkMuted)
            Text(run.spec.teachingSetting, style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
        }
        source()
    }
}

@Composable
private fun LevelStrip(run: GameRun, scores: List<StageScore>) {
    val skin = A2g.skin
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        run.spec.stages.forEachIndexed { i, stage ->
            val cleared = scores.firstOrNull { it.stageIndex == i }
            val current = i == run.stageIndex
            val label = stringResource(if (stage.transfer) R.string.games_transfer else R.string.games_practice)
            Row(Modifier.weight(1f).background(if (current) skin.card else skin.paper, pixelShape(5.dp))
                .border(if (current) 2.dp else 1.5.dp, if (current) skin.ink else skin.outline.copy(alpha = 0.6f), pixelShape(5.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    color = if (current || cleared != null) skin.ink else skin.inkMuted, maxLines = 2)
                if (cleared != null) StarsRow(cleared.stars, size = 16.dp)
            }
        }
    }
}

/** The changed-condition banner: what is different now, taken from the two stage configurations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewConditions(run: GameRun) {
    val skin = A2g.skin
    val previous = run.spec.stages.take(run.stageIndex).lastOrNull { !it.transfer && it.mechanicId == run.stage.mechanicId }?.config
    val changes = mutableListOf<String>()
    when (val now = run.stage.config) {
        is PartitionConfig -> if (previous is PartitionConfig) {
            val look = now.look()
            if (now.candidates != previous.candidates) changes += stringResource(R.string.games_changed, look.items, previous.candidates, now.candidates)
            if (now.queryBudget != previous.queryBudget) changes += stringResource(R.string.games_changed, look.query, previous.queryBudget, now.queryBudget)
        }
        is AllocationConfig -> if (previous is AllocationConfig) {
            val look = now.look()
            if (now.tokens != previous.tokens) changes += stringResource(R.string.games_changed, look.tokens, previous.tokens, now.tokens)
            if (now.firstYield != previous.firstYield) changes += stringResource(R.string.games_changed_rate, look.firstGoods, previous.firstYield, now.firstYield, look.token)
            if (now.secondYield != previous.secondYield) changes += stringResource(R.string.games_changed_rate, look.secondGoods, previous.secondYield, now.secondYield, look.token)
            if (now.firstGoal != previous.firstGoal) changes += stringResource(R.string.games_changed_goal, look.firstGoods, previous.firstGoal, now.firstGoal)
            if (now.secondGoal != previous.secondGoal) changes += stringResource(R.string.games_changed_goal, look.secondGoods, previous.secondGoal, now.secondGoal)
        }
    }
    PixelPanel(Modifier.fillMaxWidth().testTag("new_conditions"), fill = skin.hintSoft, outline = skin.hint) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelSprite(Sprites.sparkle, 22.dp)
            Text(stringResource(R.string.games_new_conditions), style = MaterialTheme.typography.titleLarge, color = skin.hint, fontWeight = FontWeight.Black)
        }
        Text(stringResource(R.string.games_new_conditions_hint), color = skin.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            changes.forEach { PixelTag(it, fill = skin.card) }
        }
    }
}

@Composable
private fun Result(run: GameRun, busy: Boolean, stars: Int?, best: Int, mood: Mood, onMove: (GameAction) -> Unit, onRestart: () -> Unit, backTo: String?, onBackTo: () -> Unit) {
    val skin = A2g.skin
    val reduced = rememberReducedMotion()
    val won = run.status == StageStatus.WON
    PixelPanel(Modifier.fillMaxWidth().testTag("game_result"), fill = if (won) skin.correctSoft else skin.wrongSoft, outline = if (won) skin.correct else skin.wrong) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (won) {
                Stamp(stringResource(if (run.completed) R.string.games_stamp_complete else R.string.games_stamp_clear), StampKind.YES, skin.correct,
                    animate = !reduced, big = run.completed)
            } else Stamp(stringResource(R.string.games_stamp_miss), StampKind.NO, skin.wrong, animate = !reduced)
        }
        Text(stringResource(when { run.completed -> R.string.games_complete; won -> R.string.games_won
            run.board is AllocationBoard -> R.string.games_lost_allocation; else -> R.string.games_lost }),
            style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("game_verdict"), color = skin.ink)
        if (won && stars != null) {
            val earned = stringResource(R.string.games_stars_earned, stars)
            StarsRow(stars, size = 40.dp, animate = !reduced, modifier = Modifier.testTag("game_stars"))
            Text(earned, color = skin.inkMuted, style = MaterialTheme.typography.bodySmall)
            if (run.completed && best > stars) Text(stringResource(R.string.games_best_stars, best), color = skin.inkMuted, style = MaterialTheme.typography.bodySmall)
        }
        when {
            run.completed || run.actionLimitReached -> {
                // Started from a route step: the way back to that Space comes first, and Play again stays one tap away.
                val fromRoute = run.completed && backTo != null
                if (fromRoute) PixelButton(stringResource(R.string.games_back_to, backTo!!), onBackTo, Modifier.fillMaxWidth().testTag("back_to_space"),
                    enabled = !busy, primary = true)
                PixelButton(stringResource(R.string.games_replay), onRestart, Modifier.fillMaxWidth().testTag("restart_game"), enabled = !busy, primary = !fromRoute)
            }
            won -> PixelButton(stringResource(R.string.games_next), { onMove(GameAction.Advance) }, Modifier.fillMaxWidth().testTag("game_advance"), enabled = !busy, primary = true)
            else -> PixelButton(stringResource(R.string.games_retry), { onMove(GameAction.Reset) }, Modifier.fillMaxWidth().testTag("retry_round"), enabled = !busy, primary = true)
        }
        if (run.completed) CompletedMoves(run)
    }
}

@Composable
private fun CompletedMoves(run: GameRun) {
    val turns = remember(run.checkpoint) {
        var current = GameRuntime.start(run.spec, run.checkpoint.seed)
        buildList {
            for (action in run.checkpoint.actions) {
                current = GameRuntime.apply(current, action)
                if (action is GameAction.Split || action == GameAction.Submit) add(action to current.board)
            }
        }
    }
    var expanded by rememberSaveable(run.checkpoint.seed) { mutableStateOf(false) }
    TextButton({ expanded = !expanded }) { Text(stringResource(R.string.games_history)) }
    if (expanded) turns.forEach { (action, board) ->
        when {
            action is GameAction.Split && board is PartitionBoard -> Text(stringResource(R.string.games_trace_split, action.after, board.eliminated, board.low, board.high))
            board is AllocationBoard -> (run.stage.config as? AllocationConfig)?.look()?.let { look ->
                Text(stringResource(R.string.games_trace_output, board.firstOutput, look.firstGoods, board.secondOutput, look.secondGoods))
            }
        }
    }
}
