package com.anything2game.app.ui.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.data.Familiarity
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.home.PaperCard
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.*
import com.anything2game.engine.session.SessionState
import kotlinx.coroutines.CancellationException

/** A reading surface over the existing package. No term extraction or AI personalization is implied. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingContent(deps: AppDependencies, packageId: String, onBack: () -> Unit, onPractice: (String, String, FrameId) -> Unit) {
    var pkg by remember(packageId) { mutableStateOf<LearningPackage?>(null) }
    var savedSession by remember(packageId) { mutableStateOf<SessionState?>(null) }
    var failed by remember(packageId) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var reading by rememberSaveable(packageId) { mutableStateOf(false) }
    LaunchedEffect(packageId, attempt) {
        failed = false
        try {
            pkg = deps.packages.load(packageId).getOrThrow()
            savedSession = deps.sessions.load(packageId)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(packageId, reading) { listState.scrollToItem(0) }
    val skin = A2g.skin
    Scaffold(containerColor = skin.paper) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } }
            if (failed) item {
                PaperCard {
                    Text(stringResource(R.string.home_load_error), color = skin.wrong)
                    Button(onClick = { attempt++ }) { Text(stringResource(R.string.home_retry)) }
                }
            }
            val p = pkg
            if (p == null && !failed) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (p != null && !failed) {
                item {
                    Text(stringResource(if (reading) R.string.reading_title else R.string.preflight_title), style = MaterialTheme.typography.headlineMedium, color = skin.ink)
                    Text(p.title, style = MaterialTheme.typography.titleMedium, color = skin.inkMuted)
                    if (p.generation.mode == GenerationMode.PRE_GENERATED) Badge(stringResource(R.string.badge_pre_generated), skin.evidence, skin.evidenceSoft)
                }
                item {
                    Text(stringResource(if (reading) R.string.reading_note else R.string.preflight_note), color = skin.inkMuted)
                }
                items(p.levels, key = { it.levelId }) { level ->
                    var familiarity by remember(packageId, p.contentVersion, level.levelId) {
                        mutableStateOf(deps.homePreferences.familiarity(packageId, p.contentVersion, level.levelId))
                    }
                    PaperCard {
                        Text(level.title, style = MaterialTheme.typography.titleLarge, color = skin.ink)
                        Text(level.goal, color = skin.inkMuted)
                        if (!reading) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Familiarity.entries.forEach { value ->
                                    FilterChip(selected = familiarity == value, onClick = {
                                        familiarity = value
                                        deps.homePreferences.setFamiliarity(packageId, p.contentVersion, level.levelId, value)
                                    }, label = { Text(stringResource(when (value) {
                                        Familiarity.KNOWN -> R.string.preflight_known
                                        Familiarity.UNSURE -> R.string.preflight_unsure
                                        Familiarity.NEW -> R.string.preflight_new
                                    })) })
                                }
                            }
                        } else {
                            if (familiarity == Familiarity.NEW || familiarity == Familiarity.UNSURE) {
                                Badge(stringResource(R.string.reading_focus), skin.hint, skin.hintSoft)
                            }
                            level.recap.points.forEach { Text("• $it", color = skin.ink) }
                            var showSources by rememberSaveable(packageId, level.levelId) { mutableStateOf(false) }
                            TextButton(onClick = { showSources = !showSources }) { Text(stringResource(if (showSources) R.string.reading_hide_source else R.string.reading_source)) }
                            if (showSources) {
                                val chunkIds = level.units.flatMap { it.item.allSourceRefs }.map { it.chunkId }.distinct()
                                chunkIds.mapNotNull(p::chunk).forEach { chunk ->
                                    HorizontalDivider(color = skin.outline)
                                    Text(stringResource(R.string.evidence_page, chunk.pageLabel ?: (chunk.pageIndex + 1).toString()), style = MaterialTheme.typography.labelMedium, color = skin.evidence)
                                    Text(chunk.text, color = skin.ink, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Button(onClick = {
                                val resume = savedSession?.takeIf { it.contentVersion == p.contentVersion && it.levelId == level.levelId && !it.isFinished }
                                onPractice(packageId, level.levelId, resume?.frameId ?: FrameId.CLASSIC)
                            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_practice_action)) }
                        }
                    }
                }
                item {
                    if (!reading) Button(onClick = { deps.homePreferences.markPrepared(p.packageId, p.contentVersion); reading = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.preflight_continue)) }
                    else TextButton(onClick = { reading = false }) { Text(stringResource(R.string.preflight_edit)) }
                }
            }
        }
    }
}
