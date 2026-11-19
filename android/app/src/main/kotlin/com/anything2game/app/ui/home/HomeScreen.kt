package com.anything2game.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.character.Avatars
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.common.languageName
import com.anything2game.app.ui.library.*
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.FrameId

/** Order is intentional: practice / prominent reading home / personal profile. */
enum class HomeTab(val label: Int, val icon: ImageVector) {
    PRACTICE(R.string.nav_practice, Icons.AutoMirrored.Outlined.FactCheck),
    LEARN(R.string.nav_learn, Icons.AutoMirrored.Outlined.MenuBook),
    PROFILE(R.string.nav_profile, Icons.Outlined.PersonOutline);
    companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: LEARN }
}

@Composable
fun HomeNavigation(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val skin = A2g.skin
    Surface(color = skin.card, shadowElevation = 8.dp) {
        NavigationBar(containerColor = skin.card, tonalElevation = 0.dp, modifier = Modifier.testTag("home_navigation")) {
            HomeTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = selected == tab, onClick = { onSelect(tab) },
                    modifier = Modifier.testTag("tab_${tab.name}"),
                    icon = {
                        if (tab == HomeTab.LEARN) {
                            Box(Modifier.background(skin.action, RoundedCornerShape(16.dp)).padding(10.dp)) {
                                Icon(tab.icon, contentDescription = null, tint = skin.onAction, modifier = Modifier.size(30.dp))
                            }
                        } else Icon(tab.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                    },
                    label = { Text(stringResource(tab.label), fontWeight = if (selected == tab) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = skin.action, selectedTextColor = skin.action,
                        unselectedIconColor = skin.inkMuted, unselectedTextColor = skin.inkMuted, indicatorColor = skin.evidenceSoft),
                )
            }
        }
    }
}

@Composable
fun HomeContent(
    tab: HomeTab, state: LibraryUiState, avatarId: String, nickname: String,
    onSelectAvatar: (String) -> Unit, onRename: (String) -> Unit,
    onPickPdf: () -> Unit, onRead: (String) -> Unit,
    onPlay: (String, String, FrameId) -> Unit, onSelectTab: (HomeTab) -> Unit, onRetry: () -> Unit,
    padding: PaddingValues = PaddingValues(), simulator: (@Composable () -> Unit)? = null, onPreparePractice: (String) -> Unit = {},
    onSwitchProfile: (() -> Unit)? = null,
    onSelectFrame: (String, FrameId) -> Unit = { _, _ -> },
    pauseWhenWalking: Boolean = false,
    onPauseWhenWalking: ((Boolean) -> Unit)? = null,
) {
    val skin = A2g.skin
    LazyColumn(
        Modifier.fillMaxSize().testTag("home_${tab.name}"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
            top = padding.calculateTopPadding() + 24.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(when (tab) {
                HomeTab.LEARN -> R.string.home_learn_title
                HomeTab.PRACTICE -> R.string.home_practice_title
                HomeTab.PROFILE -> R.string.home_profile_title
            }), style = MaterialTheme.typography.headlineMedium, color = skin.ink)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(when (tab) {
                HomeTab.LEARN -> R.string.home_learn_subtitle
                HomeTab.PRACTICE -> R.string.home_practice_subtitle
                HomeTab.PROFILE -> R.string.home_profile_subtitle
            }), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
        }
        if (tab == HomeTab.PROFILE) {
            item { ProfileIdentity(avatarId, nickname, onRename) }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.hasError) item {
            PaperCard {
                Text(stringResource(R.string.home_load_error), color = skin.wrong)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.home_retry)) }
            }
        }
        when (tab) {
            HomeTab.LEARN -> {
                item {
                    PaperCard {
                        Badge(stringResource(R.string.home_read_first), skin.evidence, skin.evidenceSoft)
                        Text(stringResource(R.string.home_upload_title), style = MaterialTheme.typography.titleLarge, color = skin.ink)
                        Text(stringResource(R.string.home_upload_body), color = skin.inkMuted, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onPickPdf, modifier = Modifier.fillMaxWidth().testTag("pick_pdf")) {
                            Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.import_pick_pdf))
                        }
                    }
                }
                item { PaperCard {
                    Badge(stringResource(R.string.tutor_fixed_badge), skin.evidence, skin.evidenceSoft)
                    Text(stringResource(R.string.tutor_demo_title), style = MaterialTheme.typography.titleLarge, color = skin.ink)
                    Text(stringResource(R.string.tutor_demo_body), color = skin.inkMuted)
                    OutlinedButton(onClick = { onRead(com.anything2game.app.data.tutor.FIXED_TUTOR_PACKAGE) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tutor_demo_action)) }
                } }
                items(state.tutorials, key = { "tutor:${it.sessionId}" }) { tutorial -> PaperCard {
                    Text(tutorial.title, style = MaterialTheme.typography.titleLarge, color = skin.ink)
                    Badge(stringResource(if (tutorial.mode == "model") R.string.tutor_model_badge else R.string.tutor_server_mock_badge), skin.action, skin.evidenceSoft)
                    OutlinedButton(onClick = { onRead(tutorial.sessionId) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tutor_resume)) }
                } }
                item { Text(stringResource(R.string.home_library), style = MaterialTheme.typography.titleLarge, color = skin.ink) }
                items(state.entries, key = { it.summary.packageId }) { entry ->
                    PaperCard {
                        PackageHeading(entry)
                        Text(pluralStringResource(R.plurals.home_chapters, entry.levels.size, entry.levels.size), color = skin.inkMuted)
                        OutlinedButton(onClick = { onRead(entry.summary.packageId) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.home_start_reading))
                        }
                    }
                }
            }
            HomeTab.PRACTICE -> {
                item { Badge(stringResource(R.string.home_classic), skin.action, skin.evidenceSoft) }
                state.practiceError?.let { error -> item { Text(stringResource(R.string.tutor_error, error), color = skin.wrong) } }
                items(state.tutorials.filter { t -> state.entries.none { it.documentId == t.documentId } }, key = { "practice:${it.sessionId}" }) { tutorial -> PaperCard {
                    Text(tutorial.title, style = MaterialTheme.typography.titleLarge, color = skin.ink)
                    Text(stringResource(R.string.tutor_practice_separate), color = skin.inkMuted)
                    if (tutorial.mode == "mock") Badge(stringResource(R.string.tutor_server_mock_badge), skin.evidence, skin.evidenceSoft)
                    if (state.preparingPractice == tutorial.sessionId) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick = { onPreparePractice(tutorial.sessionId) }, enabled = state.preparingPractice == null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tutor_prepare_practice)) }
                } }
                items(state.entries, key = { it.summary.packageId }) { entry ->
                    PaperCard {
                        PackageHeading(entry)
                        TextButton(onClick = { onRead(entry.summary.packageId) }) { Text(stringResource(R.string.home_read_first_action)) }
                        if (entry.frames.size > 1) PlayAs(entry.frames, entry.selectedFrame) { onSelectFrame(entry.summary.packageId, it) }
                        entry.levels.forEach { level ->
                            HorizontalDivider(color = skin.outline.copy(alpha = .4f))
                            Text(level.title, color = skin.ink, style = MaterialTheme.typography.titleMedium)
                            val progress = level.status
                            Text(when (progress) {
                                LevelStatus.New -> stringResource(R.string.level_status_new)
                                LevelStatus.NeedsReview -> stringResource(R.string.home_needs_review)
                                LevelStatus.Done -> stringResource(R.string.home_level_solid)
                                LevelStatus.Cleared -> stringResource(R.string.level_status_cleared)
                                is LevelStatus.InProgress -> stringResource(R.string.level_status_in_progress, progress.position, progress.total)
                            }, color = skin.inkMuted, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                onPlay(entry.summary.packageId, level.levelId, entry.frameFor(level.levelId))
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (progress is LevelStatus.InProgress) R.string.home_resume else R.string.home_practice_action))
                            }
                        }
                    }
                }
            }
            HomeTab.PROFILE -> {
                if (!state.loading) item {
                    PaperCard {
                        Text(stringResource(R.string.home_progress), style = MaterialTheme.typography.titleLarge, color = skin.ink)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat((state.entries.map { it.documentId.ifBlank { it.summary.packageId } } + state.tutorials.map { it.documentId }).distinct().size, R.string.home_stat_documents, Modifier.weight(1f))
                            Stat(state.entries.sumOf { it.practicedUnits }, R.string.home_stat_practiced, Modifier.weight(1f))
                            Stat(state.entries.sumOf { it.dueUnits }, R.string.home_stat_review, Modifier.weight(1f))
                        }
                        Text(stringResource(R.string.home_stats_note), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
                        OutlinedButton(onClick = { onSelectTab(HomeTab.PRACTICE) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.home_go_practice))
                        }
                    }
                }
                item { AvatarPicker(avatarId, onSelectAvatar) }
                item {
                    PaperCard {
                        Icon(Icons.Outlined.PhonelinkLock, null, tint = skin.action)
                        Text(stringResource(R.string.home_account_title), style = MaterialTheme.typography.titleMedium, color = skin.ink)
                        Text(stringResource(R.string.home_account_body), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
                        // Guest ↔ sample student; nothing is deleted when switching.
                        if (onSwitchProfile != null) OutlinedButton(onClick = onSwitchProfile, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.profile_switch))
                        }
                    }
                }
                if (onPauseWhenWalking != null) item {
                    PaperCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.setting_pause_walking), style = MaterialTheme.typography.titleMedium, color = skin.ink)
                                Text(stringResource(R.string.setting_pause_walking_body), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
                            }
                            androidx.compose.material3.Switch(pauseWhenWalking, onPauseWhenWalking, Modifier.testTag("setting_pause_walking"))
                        }
                    }
                }
                if (simulator != null) item {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    PaperCard {
                        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.home_demo_tools)) }
                        if (expanded) simulator()
                    }
                }
            }
        }
        if (!state.loading && !state.hasError && state.entries.isEmpty() && tab != HomeTab.PROFILE) item {
            PaperCard {
                Text(stringResource(R.string.empty_library), color = skin.inkMuted)
                TextButton(onClick = onPickPdf) { Text(stringResource(R.string.import_pick_pdf)) }
            }
        }
    }
}

@Composable
internal fun PaperCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = A2g.skin.card),
        elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun PackageHeading(entry: LibraryEntry) {
    val skin = A2g.skin
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge(languageName(entry.summary.language), skin.action, skin.paper)
        if (entry.mock) Badge(stringResource(R.string.tutor_server_mock_badge), skin.evidence, skin.evidenceSoft)
        else if (entry.summary.preGenerated) Badge(stringResource(R.string.badge_pre_generated), skin.evidence, skin.evidenceSoft)
    }
    Text(entry.summary.title, style = MaterialTheme.typography.titleLarge, color = skin.ink)
}

@Composable
private fun Stat(value: Int, label: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = A2g.skin.action)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = A2g.skin.inkMuted)
    }
}

@Composable
private fun ProfileIdentity(avatarId: String, nickname: String, onRename: (String) -> Unit) {
    val skin = A2g.skin
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(nickname) }
    PaperCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatars.of(avatarId).Draw(Mood.HAPPY, Modifier.size(80.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(nickname.ifBlank { stringResource(R.string.home_guest) }, style = MaterialTheme.typography.titleLarge, color = skin.ink)
                Badge(stringResource(R.string.home_local_profile), skin.action, skin.paper)
            }
            IconButton(onClick = { draft = nickname; editing = true }, modifier = Modifier.testTag("edit_nickname")) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.home_edit_name), tint = skin.action)
            }
        }
        Text(stringResource(R.string.home_identity_note), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
    }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.home_edit_name)) },
        text = { OutlinedTextField(value = draft, onValueChange = { draft = it.take(24) }, singleLine = true,
            label = { Text(stringResource(R.string.home_name_label)) }, supportingText = { Text("${draft.length}/24") },
            modifier = Modifier.testTag("nickname_input")) },
        confirmButton = { TextButton(onClick = { onRename(draft.trim()); editing = false }) { Text(stringResource(R.string.home_save)) } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.import_cancel)) } },
    )
}

/** "Play as": the same clues, a different thing to do with them — and when each one fits (docs/product/frames.md). */
@Composable
private fun PlayAs(frames: List<FrameId>, selected: FrameId, onSelect: (FrameId) -> Unit) {
    val skin = A2g.skin
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.practice_play_as), style = MaterialTheme.typography.labelLarge, color = skin.inkMuted)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            frames.forEach { f ->
                androidx.compose.material3.FilterChip(f == selected, { onSelect(f) },
                    { Text(stringResource(com.anything2game.app.ui.play.frameName(f))) }, Modifier.testTag("play_as_${f.name}"))
            }
        }
        Text(stringResource(com.anything2game.app.ui.play.frameDescription(selected)), style = MaterialTheme.typography.bodyMedium, color = skin.ink)
        Text(stringResource(com.anything2game.app.ui.play.frameBestFor(selected)), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
    }
}
