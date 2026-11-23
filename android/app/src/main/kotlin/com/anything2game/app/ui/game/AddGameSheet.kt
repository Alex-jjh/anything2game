package com.anything2game.app.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anything2game.app.R
import com.anything2game.app.ui.character.rememberReducedMotion
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.game.ReviewedGames
import kotlinx.coroutines.delay

private const val INPUT = 0
private const val GENERATING = 1
private const val CHOOSING = 2
private val steps = listOf(R.string.gen_step_read, R.string.gen_step_mechanic, R.string.gen_step_levels, R.string.gen_step_check)
internal const val STEP_MILLIS = 700L

/**
 * "Add a game". The sequence is a demonstration, not a server call: it shows the steps the generator takes, then opens the closest
 * game that was prepared in advance (or lets the player choose one). Everything on it says so ("Demo · pre-generated").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddGameSheet(presets: List<InstalledGame>, language: String, onClose: () -> Unit, onOpen: (ContentVersion) -> Unit,
    previewStep: Int? = null, previewTopic: String? = null) {
    val skin = A2g.skin
    val reduced = rememberReducedMotion()
    var topic by rememberSaveable { mutableStateOf(previewTopic.orEmpty()) }
    // A sequence in flight cannot survive recreation, so the phase is not saved; the typed topic is.
    var phase by remember { mutableIntStateOf(if (previewStep != null) GENERATING else INPUT) }
    var step by remember { mutableIntStateOf(previewStep ?: 0) }
    val installed = presets.associateBy { it.spec.gameId }
    BackHandler { if (phase == GENERATING) phase = INPUT else onClose() }
    // previewStep freezes the sequence at one step, for screenshots.
    if (phase == GENERATING && previewStep == null) LaunchedEffect(Unit) {
        for (i in steps.indices) { step = i; if (!reduced) delay(STEP_MILLIS) }
        step = steps.size
        if (!reduced) delay(250)
        val match = ReviewedGames.find(topic)?.spec(language)?.gameId?.let(installed::get)
        if (match != null) { topic = ""; onOpen(match.version) } else phase = CHOOSING
    }
    Box(Modifier.fillMaxSize().testTag("add_sheet")) {
        Box(Modifier.fillMaxSize().background(Color(0x99231F2B)).clickable(onClickLabel = stringResource(R.string.add_close), onClick = onClose))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)) {
            PixelPanel(Modifier.fillMaxWidth(), fill = skin.paper) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.add_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    PixelTag(stringResource(R.string.games_demo_badge), fill = skin.card, ink = skin.inkMuted)
                    PixelButton("×", onClose, Modifier.semantics { contentDescription = "close" }.testTag("add_close"))
                }
                when (phase) {
                    INPUT -> {
                        Text(stringResource(R.string.add_prompt), color = skin.ink)
                        OutlinedTextField(topic, { topic = it.take(120) }, Modifier.fillMaxWidth().testTag("add_topic"), singleLine = true,
                            label = { Text(stringResource(R.string.games_topic)) })
                        Text(stringResource(R.string.add_suggestions), color = skin.inkMuted, style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReviewedGames.catalog.take(6).forEach { entry ->
                                val name = entry.conceptName(language)
                                Text(name, Modifier.testTag("topic_chip_${entry.key}").clickable { topic = name }.background(skin.evidenceSoft, pixelShape(5.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp).heightIn(min = 24.dp), color = skin.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                        PixelButton(stringResource(R.string.add_generate), { phase = GENERATING }, Modifier.fillMaxWidth().testTag("add_generate"),
                            enabled = topic.isNotBlank(), primary = true)
                    }
                    GENERATING -> Generating(topic, step, reduced)
                    else -> Chooser(topic, presets, language, onOpen)
                }
            }
        }
    }
}

@Composable
private fun Generating(topic: String, step: Int, reduced: Boolean) {
    val skin = A2g.skin
    val ratio by animateFloatAsState(step / steps.size.toFloat(), if (reduced) snap() else tween(500), label = "generation")
    Column(Modifier.testTag("generating"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.gen_title, topic), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().height(14.dp).background(skin.card, pixelShape(4.dp))) {
            Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().background(skin.action, pixelShape(4.dp)))
        }
        steps.forEachIndexed { i, label ->
            val done = i < step; val current = i == step
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelSprite(if (done) Sprites.star else if (current) Sprites.sparkle else Sprites.coin, 22.dp, dim = !done && !current)
                Text(stringResource(label), color = if (done || current) skin.ink else skin.inkMuted, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun Chooser(topic: String, presets: List<InstalledGame>, language: String, onOpen: (ContentVersion) -> Unit) {
    val skin = A2g.skin
    Column(Modifier.testTag("chooser"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.add_no_match, topic), color = skin.inkMuted)
        Text(stringResource(R.string.add_choose), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        presets.forEach { game ->
            Row(Modifier.fillMaxWidth().testTag("chooser_${game.spec.gameId}").clickable { onOpen(game.version) }
                .background(skin.card, pixelShape(6.dp)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GameIllustration(game.spec, Modifier.width(84.dp))
                Column(Modifier.weight(1f)) {
                    Text(game.spec.title, fontWeight = FontWeight.Bold, color = skin.ink)
                    game.entry?.let { Text(it.conceptName(language), color = skin.inkMuted, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
