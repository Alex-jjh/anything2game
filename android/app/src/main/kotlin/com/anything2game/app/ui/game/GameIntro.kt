package com.anything2game.app.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anything2game.app.R
import com.anything2game.app.ui.character.LocalAvatar
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.character.rememberReducedMotion
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.game.*
import kotlinx.coroutines.delay

/** The tiles of the "how it works" picture: the row halves five times towards one tile (16, 8, 4, 2, 1). */
private val HALVING_ROWS = listOf(1..16, 9..16, 9..12, 11..12, 11..11)
private const val HALVING_TARGET = 11

/** The icon a game is known by: its tiles' picture, or the first of its two places. */
internal fun GameSpec.mainIcon(): String = when (val c = stages.first().config) {
    is PartitionConfig -> c.theme?.icon ?: "locker"
    is AllocationConfig -> c.theme?.firstIcon ?: "bowl"
}

/**
 * The "Quick look": a full-screen story told before a game, one short scene per tap (or swipe), with a progress bar of segments on top,
 * the player's character reacting and a big Play button on the last scene. [replay] is a second look from the game or its tile, so the
 * top button says Close rather than Skip. Back steps to the previous scene and leaves from the first one.
 */
@Composable
internal fun GameIntro(spec: GameSpec, replay: Boolean, onPlay: () -> Unit, onSkip: () -> Unit, onExit: () -> Unit, onSource: (EvidenceRef) -> Unit) {
    var index by rememberSaveable(spec.gameId) { mutableIntStateOf(0) }
    val safe = index.coerceIn(0, spec.intro.lastIndex)
    BackHandler { if (safe > 0) index = safe - 1 else onExit() }
    GameIntroContent(spec, safe, { index = it }, replay, onPlay, onSkip, onSource)
}

/** The stateless layout, so the tests and the screenshots render exactly what the player sees. [frozenPhase] stops the pictures' animation on one frame. */
@Composable
internal fun GameIntroContent(spec: GameSpec, index: Int, onIndex: (Int) -> Unit, replay: Boolean, onPlay: () -> Unit, onSkip: () -> Unit,
    onSource: (EvidenceRef) -> Unit, modifier: Modifier = Modifier, frozenPhase: Int? = null) {
    val skin = A2g.skin
    val reduced = rememberReducedMotion()
    val scenes = spec.intro
    val scene = scenes[index]
    val last = index == scenes.lastIndex
    val next = { if (!last) onIndex(index + 1) }
    val previous = { if (index > 0) onIndex(index - 1) }
    val scale = LocalDensity.current.fontScale
    val progress = stringResource(R.string.intro_progress_cd, index + 1, scenes.size)
    val source = spec.evidenceRefs.firstOrNull()
    val gestures = Modifier
        .pointerInput(index) { detectTapGestures { at -> if (at.x < size.width * 0.28f) previous() else next() } }
        .pointerInput(index) {
            var travelled = 0f
            detectHorizontalDragGestures(onDragStart = { travelled = 0f }, onDragEnd = { if (travelled < -60f) next() else if (travelled > 60f) previous() },
                onHorizontalDrag = { _, delta -> travelled += delta })
        }
    BoxWithConstraints(modifier.fillMaxSize().background(skin.paper).windowInsetsPadding(WindowInsets.safeDrawing).testTag("game_intro")) {
        val landscape = maxWidth > maxHeight
        val screenHeight = maxHeight
        val stageHeight = (maxHeight * (if (scale >= 1.3f) 0.26f else 0.33f)).coerceIn(190.dp, 280.dp)
        val story: @Composable (Modifier) -> Unit = { m ->
            AnimatedContent(index, m, transitionSpec = {
                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else (slideInHorizontally(tween(220)) { w -> if (targetState > initialState) w / 5 else -w / 5 } + fadeIn(tween(220))) togetherWith fadeOut(tween(120))
            }, label = "intro-scene") { i ->
                val s = scenes[i]
                if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stage(spec, s, reduced, frozenPhase, Modifier.weight(0.46f).fillMaxHeight())
                    SceneText(s, Modifier.weight(0.54f).fillMaxHeight(), source.takeIf { last || s.kind == IntroKind.IDEA }, onSource)
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Large system text needs the room: the picture shrinks, except where it is the explanation itself.
                    val height = if (scale >= 1.3f && s.kind != IntroKind.HOW_IT_WORKS) (screenHeight * 0.2f).coerceAtLeast(130.dp) else stageHeight
                    Stage(spec, s, reduced, frozenPhase, Modifier.fillMaxWidth().height(height))
                    SceneText(s, Modifier.weight(1f).fillMaxWidth(), source.takeIf { last || s.kind == IntroKind.IDEA }, onSource)
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().semantics { contentDescription = progress }.testTag("intro_progress"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(scenes.size) { i ->
                    Box(Modifier.weight(1f).height(6.dp).background(if (i <= index) skin.action else skin.outline.copy(alpha = 0.35f), pixelShape(2.dp)))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(spec.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = skin.inkMuted, maxLines = 1)
                if (!last || replay) TextButton(onSkip, Modifier.testTag("intro_skip")) {
                    Text(stringResource(if (replay) R.string.intro_close else R.string.intro_skip), color = skin.ink, fontWeight = FontWeight.Bold)
                }
            }
            story(Modifier.weight(1f).fillMaxWidth().then(gestures))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) PixelButton(stringResource(R.string.intro_prev), previous, Modifier.testTag("intro_prev"))
                if (last) PixelButton(stringResource(R.string.intro_play), onPlay, Modifier.weight(1f).heightIn(min = 56.dp).testTag("intro_play"), primary = true)
                else PixelButton(stringResource(R.string.intro_next), next, Modifier.weight(1f).testTag("intro_next"), primary = true)
            }
        }
    }
}

@Composable
private fun SourceChip(ref: EvidenceRef, onClick: () -> Unit) {
    val title = when (ref) { is EvidenceRef.Reviewed -> ref.sourceTitle; is EvidenceRef.External -> ref.title; is EvidenceRef.Pdf -> ref.quote.take(40) }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 6.dp).clickable(onClick = onClick).testTag("intro_source"), verticalAlignment = Alignment.CenterVertically) {
        PixelTag(stringResource(R.string.intro_source, title), fill = A2g.skin.evidenceSoft, large = true)
    }
}

@Composable
private fun SceneText(scene: IntroScene, modifier: Modifier, source: EvidenceRef?, onSource: (EvidenceRef) -> Unit) {
    val skin = A2g.skin
    Column(modifier.verticalScroll(rememberScrollState()).testTag("intro_text"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PixelTag(stringResource(when (scene.kind) {
            IntroKind.HOOK -> R.string.intro_kind_hook; IntroKind.STORY -> R.string.intro_kind_story; IntroKind.IDEA -> R.string.intro_kind_idea
            IntroKind.HOW_IT_WORKS -> R.string.intro_kind_how; IntroKind.HOW_TO_PLAY -> R.string.intro_kind_play }), fill = skin.card)
        Text(scene.title, Modifier.testTag("intro_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = skin.ink)
        if (scene.kind == IntroKind.HOW_TO_PLAY) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("intro_body")) {
                scene.body.lines().filter { it.isNotBlank() }.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        PixelSprite(Sprites.star, 18.dp, Modifier.padding(top = 5.dp))
                        Text(labelled(line, skin.evidence), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 27.sp), color = skin.ink)
                    }
                }
            }
        } else Text(scene.body, Modifier.testTag("intro_body"), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 23.sp, lineHeight = 33.sp), color = skin.ink)
        if (source != null) SourceChip(source) { onSource(source) }
    }
}

/** "Goal: find it" with the label in bold colour. Both the ASCII and the full-width colon end a label. */
private fun labelled(line: String, colour: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    val at = line.indexOfFirst { it == ':' || it == '：' }
    if (at in 1..12) {
        withStyle(SpanStyle(fontWeight = FontWeight.Black, color = colour)) { append(line.substring(0, at + 1)) }
        append(line.substring(at + 1))
    } else append(line)
}

// ---- the picture on top of each scene -------------------------------------------------------------------------------------

@Composable
private fun Stage(spec: GameSpec, scene: IntroScene, reduced: Boolean, frozenPhase: Int?, modifier: Modifier) {
    val (sky, ground) = sceneColours(spec.mainIcon())
    val mood = when (scene.kind) {
        IntroKind.HOOK -> Mood.THINKING; IntroKind.STORY -> Mood.IDLE; IntroKind.IDEA -> Mood.SURPRISED
        IntroKind.HOW_IT_WORKS -> Mood.THINKING; IntroKind.HOW_TO_PLAY -> Mood.HAPPY
    }
    val picture = stringResource(R.string.intro_picture_cd)
    BoxWithConstraints(modifier.background(sky, pixelShape(8.dp)).border(2.dp, A2g.skin.ink, pixelShape(8.dp)).semantics { contentDescription = picture }.testTag("intro_stage")) {
        val avatar = (maxHeight * 0.3f).coerceIn(48.dp, 72.dp)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(avatar * 0.62f).background(ground))
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(maxHeight - avatar * 0.62f).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            val config = spec.stages.first().config
            when {
                scene.visual == IntroVisuals.HALVING -> HalvingVisual(spec.mainIcon(), reduced, frozenPhase)
                scene.visual == IntroVisuals.TRADE && config is AllocationConfig -> TradeVisual(config, reduced, frozenPhase)
                scene.kind == IntroKind.HOOK -> HookVisual(spec, reduced)
                scene.kind == IntroKind.IDEA -> IdeaVisual(spec.mainIcon(), reduced)
                scene.kind == IntroKind.HOW_TO_PLAY -> PlayVisual(config)
                else -> GameIllustration(spec, Modifier.fillMaxWidth(0.8f))
            }
        }
        LocalAvatar.current.Draw(mood, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 2.dp).size(avatar).testTag("intro_avatar"), pokeable = false)
    }
}

@Composable
private fun IntroTile(sprite: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, text: String? = null) {
    val skin = A2g.skin
    Box(modifier.size(size).background(skin.card, pixelShape(8.dp)).border(2.5.dp, skin.ink, pixelShape(8.dp)), contentAlignment = Alignment.Center) {
        if (text != null) Text(text, fontSize = (size.value * 0.6f).sp, fontWeight = FontWeight.Black, color = skin.ink)
        else PixelSprite(sprite, size * 0.62f)
    }
}

@Composable
private fun HookVisual(spec: GameSpec, reduced: Boolean) {
    val bounce by animateFloatAsState(1f, if (reduced) snap() else tween(400), label = "hook")
    val config = spec.stages.first().config
    Row(Modifier.graphicsLayer { scaleX = bounce; scaleY = bounce }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        when (config) {
            is PartitionConfig -> { val icon = spec.mainIcon(); IntroTile(icon, 64.dp); IntroTile(null, 76.dp, text = "?"); IntroTile(icon, 64.dp) }
            is AllocationConfig -> { IntroTile(config.theme?.firstIcon ?: "bowl", 70.dp); IntroTile(null, 76.dp, text = "?"); IntroTile(config.theme?.secondIcon ?: "cup", 70.dp) }
        }
    }
}

@Composable
private fun IdeaVisual(icon: String, reduced: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        IntroTile(icon, 92.dp)
        PixelSprite(Sprites.sparkle, 34.dp, Modifier.align(Alignment.TopEnd).offset(30.dp, (-18).dp))
        PixelSprite(Sprites.sparkle, 24.dp, Modifier.align(Alignment.BottomStart).offset((-26).dp, 14.dp))
        PixelSprite(Sprites.sparkle, 20.dp, Modifier.align(Alignment.TopStart).offset((-30).dp, (-10).dp))
    }
}

@Composable
private fun PlayVisual(config: StageConfig) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (config) {
            is PartitionConfig -> HeartsRow(config.queryBudget, config.queryBudget, 30.dp, description = "")
            is AllocationConfig -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(config.tokens) { PixelSprite(config.theme?.tokenIcon ?: "coin", 26.dp) }
            }
        }
        StarsRow(3, 44.dp)
    }
}

/** A step counter that walks 0..[last] and holds on the end, then starts over; fixed at the end for reduced motion and on [frozen] for tests. */
@Composable
private fun rememberPhase(frozen: Int?, reduced: Boolean, last: Int, stepMs: Long, holdMs: Long): Int {
    var phase by remember(frozen, reduced, last) { mutableIntStateOf(frozen ?: if (reduced) last else 0) }
    if (frozen == null && !reduced) LaunchedEffect(last) {
        while (true) {
            delay(stepMs)
            phase = if (phase >= last) 0 else phase + 1
            if (phase == last) delay(holdMs)
        }
    }
    return phase
}

/** 16 tiles, then the half that cannot hold the target greys out, again and again until one tile is left. */
@Composable
private fun HalvingVisual(icon: String, reduced: Boolean, frozen: Int?) {
    val skin = A2g.skin
    val phase = rememberPhase(frozen, reduced, HALVING_ROWS.lastIndex, 900, 1800)
    Column(Modifier.fillMaxWidth().testTag("intro_halving"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        HALVING_ROWS.forEachIndexed { k, range ->
            val visible by animateFloatAsState(if (phase >= k) 1f else 0f, if (reduced) snap() else tween(280), label = "halving-row")
            val mid = (range.first + range.last) / 2
            Row(Modifier.fillMaxWidth().height(26.dp).graphicsLayer { alpha = visible }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (t in 1..16) {
                        val live = t in range
                        val found = k == HALVING_ROWS.lastIndex && t == HALVING_TARGET
                        Box(Modifier.weight(1f).fillMaxHeight()
                            .background(when { found -> androidx.compose.ui.graphics.Color(0xFFFFE9A6); live -> skin.card; else -> skin.paper }, pixelShape(2.dp))
                            .border(if (found) 2.dp else 1.5.dp, if (live) skin.ink else skin.outline.copy(alpha = 0.4f), pixelShape(2.dp))
                            .drawBehind { if (k < HALVING_ROWS.lastIndex && t == mid) drawRect(skin.evidence, Offset(size.width - 3.dp.toPx(), -2.dp.toPx()), Size(3.dp.toPx(), size.height + 4.dp.toPx())) },
                            contentAlignment = Alignment.Center) {
                            if (found || (k == 0)) PixelSprite(icon, 14.dp, dim = !live)
                        }
                    }
                }
                Text(range.count().toString(), Modifier.width(26.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Black, color = skin.ink, fontSize = 15.sp)
            }
        }
    }
}

/** Two places and one token that moves from the second to the first: what the first one gains, the other one loses. */
@Composable
private fun TradeVisual(config: AllocationConfig, reduced: Boolean, frozen: Int?) {
    val skin = A2g.skin
    val look = config.look()
    val phase = rememberPhase(frozen, reduced, 1, 1400, 1800)
    val before = config.tokens / 2
    val first = before + phase
    val second = config.tokens - first
    Row(Modifier.fillMaxWidth().testTag("intro_trade"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TradeZone(look.firstZone, look.firstIcon, look.tokenIcon, first, first * config.firstYield, if (phase == 1) "+${config.firstYield}" else null, skin.correct, Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PixelSprite(look.tokenIcon, 26.dp)
            Text("←", fontSize = 24.sp, fontWeight = FontWeight.Black, color = skin.ink)
        }
        TradeZone(look.secondZone, look.secondIcon, look.tokenIcon, second, second * config.secondYield, if (phase == 1) "−${config.secondYield}" else null, skin.wrong, Modifier.weight(1f))
    }
}

@Composable
private fun TradeZone(name: String, icon: String, token: String, tokens: Int, output: Int, delta: String?, deltaColour: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val skin = A2g.skin
    Column(modifier.background(skin.card, pixelShape(6.dp)).border(2.dp, skin.ink, pixelShape(6.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, fontWeight = FontWeight.Bold, color = skin.ink, fontSize = 13.sp, maxLines = 1)
        Row(Modifier.heightIn(min = 18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { repeat(tokens) { PixelSprite(token, 16.dp) } }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelSprite(icon, 24.dp)
            Text("$output", fontWeight = FontWeight.Black, color = skin.ink, fontSize = 20.sp)
            Text(delta.orEmpty(), fontWeight = FontWeight.Black, color = deltaColour, fontSize = 17.sp, modifier = Modifier.width(34.dp))
        }
    }
}
