package com.anything2game.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.data.tutor.TutorProgress
import com.anything2game.app.ui.character.Avatars
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.library.*
import com.anything2game.app.ui.play.presentation
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.FrameId

/** Spaces is the landing tab for fresh installations and both former learning tabs. */
enum class HomeTab(val label: Int, val icon: ImageVector) {
    PLAYGROUND(R.string.nav_playground, Icons.Outlined.SportsEsports),
    SPACES(R.string.nav_spaces, Icons.AutoMirrored.Outlined.MenuBook),
    PROFILE(R.string.nav_profile, Icons.Outlined.PersonOutline);
    companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: SPACES }
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
                        if (tab == HomeTab.SPACES) {
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
fun ProfileContent(
    state: LibraryUiState, avatarId: String, nickname: String,
    onSelectAvatar: (String) -> Unit, onRename: (String) -> Unit, onSpaces: () -> Unit, onRetry: () -> Unit,
    padding: PaddingValues = PaddingValues(), simulator: (@Composable () -> Unit)? = null,
    onSwitchProfile: (() -> Unit)? = null, pauseWhenWalking: Boolean = false,
    onPauseWhenWalking: ((Boolean) -> Unit)? = null,
) {
    val skin = A2g.skin
    LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("home_PROFILE"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.home_profile_title), style = MaterialTheme.typography.headlineMedium) }
        item { ProfileIdentity(avatarId, nickname, onRename) }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.hasError) item { TextButton(onClick = onRetry) { Text(stringResource(R.string.home_retry)) } }
        state.activityProgress?.let { progress -> item { PaperCard {
            Text(stringResource(R.string.study_record), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.completed_days, pluralStringResource(R.plurals.completed_activities, progress.completed.size, progress.completed.size), pluralStringResource(R.plurals.learning_days, progress.days.size, progress.days.size)))
            if (progress.days.isNotEmpty()) Text(progress.days.sortedDescending().take(7).joinToString("  ·  "), color = skin.action)
        } } }
        if (state.legacyUnits > 0) item { Text(pluralStringResource(R.plurals.history_units, state.legacyUnits, state.legacyUnits)) }
        if (!state.loading) item {
            PaperCard {
                Text(stringResource(R.string.home_progress), style = MaterialTheme.typography.titleLarge, color = skin.ink)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat((state.entries.map { it.documentId.ifBlank { it.summary.packageId } } + state.tutorials.map { it.documentId }).distinct().size, R.string.home_stat_documents, Modifier.weight(1f))
                    Stat(state.entries.sumOf { it.practicedUnits }, R.string.home_stat_practiced, Modifier.weight(1f))
                    Stat(state.entries.sumOf { it.dueUnits }, R.string.status_due, Modifier.weight(1f))
                }
                Text(stringResource(R.string.home_stats_note), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
                OutlinedButton(onClick = onSpaces, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nav_spaces))
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
        if (simulator != null) item(key = "demo-tools") {
            var expanded by rememberSaveable { mutableStateOf(false) }
            PaperCard {
                TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.home_demo_tools)) }
                if (expanded) simulator()
            }
        }
    }
}

/** Continue a material's saved explanation at its actual position. */
@Composable
internal fun learnActionLabel(progress: TutorProgress?): String = when {
    progress == null -> stringResource(R.string.tutor_start_explanation)
    progress.finished -> stringResource(R.string.tutor_card_explained)
    progress.explaining -> stringResource(R.string.tutor_card_continue_concept, progress.position + 1, progress.total)
    else -> stringResource(R.string.tutor_card_continue)
}

@Composable
internal fun PaperCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = A2g.skin.card),
        elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
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
internal fun PlayAs(frames: List<FrameId>, selected: FrameId, onSelect: (FrameId) -> Unit) {
    val skin = A2g.skin
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.practice_play_as), style = MaterialTheme.typography.labelLarge, color = skin.inkMuted)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            frames.forEach { f ->
                androidx.compose.material3.FilterChip(f == selected, { onSelect(f) },
                    { Text(stringResource(f.presentation().name)) }, Modifier.heightIn(min = 48.dp).testTag("play_as_${f.name}"))
            }
        }
        Text(stringResource(selected.presentation().description), style = MaterialTheme.typography.bodyMedium, color = skin.ink)
        Text(stringResource(selected.presentation().bestFor), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
    }
}
