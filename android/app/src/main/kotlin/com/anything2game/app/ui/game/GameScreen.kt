package com.anything2game.app.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.data.content.ContentLibrary
import com.anything2game.app.data.learning.LearningProgress
import com.anything2game.app.ui.home.PaperCard
import com.anything2game.app.ui.space.ActivityEvidence
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.game.*
import com.anything2game.engine.game.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.random.Random

internal class GameViewModel(private val content: ContentLibrary, private val progress: LearningProgress,
    private val version: ContentVersion) : ViewModel() {
    var run by mutableStateOf<GameRun?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    init { load() }
    fun load(replay: Boolean = false) = save {
        val spec = requireNotNull(content.game(version))
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
internal fun GameScreen(deps: AppDependencies, version: ContentVersion, onBack: () -> Unit) {
    val vm = viewModel(key = "game:${deps.profiles.active.value?.id}:$version") {
        GameViewModel(requireNotNull(deps.content), requireNotNull(deps.learningProgress), version)
    }
    BackHandler { if (!vm.busy) onBack() }
    val run = vm.run
    LaunchedEffect(run?.spec, version) { if (run != null) deps.homePreferences.lastGame = version }
    var evidence by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(containerColor = A2g.skin.paper) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp).testTag("game_board"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onBack, enabled = !vm.busy) { Text(stringResource(R.string.action_back)) }
            if (vm.busy && run == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let {
                Text(stringResource(R.string.activity_missing) + " " + it, color = A2g.skin.wrong)
                OutlinedButton({ vm.load() }) { Text(stringResource(R.string.home_retry)) }
            }
            if (run != null) {
                Text(run.spec.title, style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(if (run.stage.transfer) R.string.games_transfer else R.string.games_practice), color = A2g.skin.action)
                Text(run.stage.goal, style = MaterialTheme.typography.titleMedium)
                val playing = !vm.busy && run.status == StageStatus.PLAYING
                val move: (GameAction) -> Unit = { vm.move(run, it) }
                when (val b = run.board) {
                    is PartitionBoard -> PartitionControls(b, run.stage.config as PartitionConfig, playing, move)
                    is AllocationBoard -> AllocationControls(b, run.stage.config as AllocationConfig, run.checkpoint.actions.lastOrNull(), playing, move)
                }
                if (run.checkpoint.actions.lastOrNull() == GameAction.Help) Text(stringResource(
                    if (run.board is PartitionBoard) R.string.games_split_hint else R.string.games_token_hint))
                if (playing) OutlinedButton({ move(GameAction.Help) }) { Text(stringResource(R.string.games_help)) }
                if (run.status != StageStatus.PLAYING) {
                    Text(stringResource(when { run.completed -> R.string.games_complete; run.status == StageStatus.WON -> R.string.games_won; else -> R.string.games_lost }),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("game_verdict"))
                }
                when {
                    run.completed -> Button({ vm.load(true) }, enabled = !vm.busy) { Text(stringResource(R.string.games_replay)) }
                    run.status == StageStatus.WON -> Button({ move(GameAction.Advance) }, Modifier.testTag("game_advance"), enabled = !vm.busy) { Text(stringResource(R.string.games_next)) }
                    else -> TextButton({ move(GameAction.Reset) }, enabled = !vm.busy && run.checkpoint.actions.isNotEmpty()) { Text(stringResource(R.string.games_reset)) }
                }
                if (run.completed) CompletedMoves(run)
                Text(run.spec.teachingSetting, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.games_record, run.checkpoint.actions.size), color = A2g.skin.inkMuted)
                run.spec.evidenceRefs.filter { it.evidenceId in run.stage.evidenceIds }.forEach { ref ->
                    OutlinedButton({ evidence = ref.evidenceId }) { Text(stringResource(R.string.source_open)) }
                    if (evidence == ref.evidenceId) ActivityEvidence(requireNotNull(deps.materials), ref) { evidence = null }
                }
            }
        }
    }
}

@Composable
private fun PartitionControls(b: PartitionBoard, config: PartitionConfig, enabled: Boolean, move: (GameAction) -> Unit) {
    var choice by rememberSaveable(b.low, b.high) { mutableStateOf<Int?>(null) }
    val split = choice ?: ((b.low + b.high) / 2)
    PaperCard {
        Text(stringResource(R.string.games_queries, b.queriesLeft, b.low, b.high), style = MaterialTheme.typography.titleMedium)
        if (enabled && choice == null) Text(stringResource(R.string.games_choose_cut))
        for (row in (1..config.candidates).chunked(4)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { n ->
                val active = n in b.low..b.high
                val fill = when { !active -> A2g.skin.paper; choice != null && n <= split -> A2g.skin.action; else -> A2g.skin.evidenceSoft }
                val ink = if (active && n <= split) A2g.skin.onAction else A2g.skin.inkMuted
                val cutLabel = stringResource(R.string.games_cut_after, n)
                val group = stringResource(when { !active -> R.string.games_door_out; choice == null -> R.string.games_door_in; n <= split -> R.string.games_door_left; else -> R.string.games_door_right })
                Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(enabled = enabled && n in b.low until b.high,
                    onClickLabel = cutLabel) { choice = n }.testTag("cut_$n").semantics { stateDescription = group }.background(fill, RoundedCornerShape(6.dp)).border(1.dp, A2g.skin.outline, RoundedCornerShape(6.dp))
                    .padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(18.dp).height(2.dp).background(ink))
                    Text(n.toString(), color = ink, style = MaterialTheme.typography.titleMedium)
                }
            }
            repeat(4-row.size) { Spacer(Modifier.weight(1f)) }
        }
        if (b.eliminated > 0) Text(stringResource(R.string.games_eliminated, b.eliminated), color = A2g.skin.correct)
        if (b.low < b.high) {
            choice?.let { Text(stringResource(R.string.games_groups, it-b.low+1, b.high-it)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val left = stringResource(R.string.games_less)
                OutlinedButton({ choice = (split-1).coerceAtLeast(b.low) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = left }, enabled = enabled && (choice == null || split > b.low)) { Text("−") }
                Text(stringResource(R.string.games_split, split), Modifier.weight(1f).padding(horizontal = 8.dp))
                val right = stringResource(R.string.games_more)
                OutlinedButton({ choice = (split+1).coerceAtMost(b.high-1) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = right }, enabled = enabled && (choice == null || split < b.high-1)) { Text("+") }
            }
            Button({ move(GameAction.Split(split)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("split_submit"), enabled = enabled && choice != null) { Text(stringResource(R.string.games_ask)) }
        }
    }
}

@Composable
private fun AllocationControls(b: AllocationBoard, config: AllocationConfig, last: GameAction?, enabled: Boolean, move: (GameAction) -> Unit) {
    PaperCard {
        Text(stringResource(R.string.games_tokens, b.available), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(config.tokens) { i ->
            Box(Modifier.size(16.dp).background(if (i < b.available) A2g.skin.evidence else A2g.skin.paper, RoundedCornerShape(100))
                .border(1.dp, A2g.skin.outline, RoundedCornerShape(100)))
        } }
        val zones = if (b.firstOnLeft) Zone.entries else Zone.entries.reversed()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            zones.forEach { zone ->
                val first = zone == Zone.FIRST
                Column(Modifier.weight(1f).background(if (first) A2g.skin.evidenceSoft else A2g.skin.correctSoft,
                    RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.games_product, if (first) "A" else "B"), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.games_yield, if (first) config.firstYield else config.secondYield))
                    Text(stringResource(R.string.games_output, if (first) b.firstOutput else b.secondOutput, if (first) config.firstGoal else config.secondGoal))
                    Button({ move(GameAction.Place(zone)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("place_${zone.name}"),
                        enabled = enabled && b.available > 0, contentPadding = PaddingValues(4.dp)) { Text(stringResource(R.string.games_place)) }
                }
            }
        }
        if (last is GameAction.Place) {
            val first = last.zone == Zone.FIRST
            Text(stringResource(R.string.games_tradeoff, if (first) config.firstYield else config.secondYield,
                if (first) "A" else "B", if (first) config.secondYield else config.firstYield, if (first) "B" else "A"), color = A2g.skin.action)
        }
        OutlinedButton({ move(GameAction.Undo) }, enabled = enabled && b.placements.isNotEmpty()) { Text(stringResource(R.string.games_undo)) }
        Button({ move(GameAction.Submit) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("allocation_submit"), enabled = enabled) { Text(stringResource(R.string.games_deliver)) }
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
            board is AllocationBoard -> Text(stringResource(R.string.games_trace_output, board.firstOutput, board.secondOutput))
        }
    }
}
