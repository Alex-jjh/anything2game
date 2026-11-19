package com.anything2game.app.ui.library

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.common.languageName
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.play.frameDescription
import com.anything2game.app.ui.play.frameName
import com.anything2game.contract.FrameId

/** Stateless: everything comes in as parameters, so it can be previewed and screenshot-tested. */
@Composable
fun LibraryContent(
    state: LibraryUiState,
    onOpenLevel: (packageId: String, levelId: String, frameId: FrameId) -> Unit,
    onSelectFrame: (packageId: String, frameId: FrameId) -> Unit,
    onPickPdf: () -> Unit,
    footer: @Composable () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    avatarId: String? = null,
    onSelectAvatar: ((String) -> Unit)? = null,
) {
    val skin = A2g.skin
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = contentPadding.calculateTopPadding() + 24.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineSmall, color = skin.ink)
            Text(stringResource(R.string.library_subtitle), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
        }
        if (onSelectAvatar != null) item { AvatarPicker(avatarId, onSelectAvatar) }
        if (!state.loading && state.entries.isEmpty()) {
            item { Text(stringResource(R.string.empty_library), color = skin.inkMuted) }
        }
        items(state.entries, key = { it.summary.packageId }) { entry -> PackageCard(entry, onOpenLevel, onSelectFrame) }
        item { ImportCard(onPickPdf) }
        item { footer() }
    }
}

@Composable
private fun PackageCard(entry: LibraryEntry, onOpenLevel: (String, String, FrameId) -> Unit, onSelectFrame: (String, FrameId) -> Unit) {
    val skin = A2g.skin
    Card(colors = CardDefaults.cardColors(containerColor = skin.card), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.summary.title, style = MaterialTheme.typography.titleLarge, color = skin.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(languageName(entry.summary.language), skin.action, skin.paper)
                if (entry.summary.preGenerated) Badge(stringResource(R.string.badge_pre_generated), skin.evidence, skin.evidenceSoft)
            }
            FramePicker(entry.frames, entry.selectedFrame) { onSelectFrame(entry.summary.packageId, it) }
            entry.levels.forEachIndexed { i, level ->
                if (i > 0) HorizontalDivider(color = skin.outline)
                LevelRow(level) { onOpenLevel(entry.summary.packageId, level.levelId, entry.selectedFrame) }
            }
        }
    }
}

/** The frame is the one game choice the player makes (docs/product/game-concepts.md §7); mechanics are never chosen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FramePicker(frames: List<FrameId>, selected: FrameId, onSelect: (FrameId) -> Unit) {
    val skin = A2g.skin
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.frame_label), style = MaterialTheme.typography.labelMedium, color = skin.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            frames.forEach { f -> FilterChip(f == selected, { onSelect(f) }, { Text(stringResource(frameName(f))) }) }
        }
        Text(stringResource(frameDescription(selected)), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
    }
}

@Composable
private fun LevelRow(level: LevelEntry, onClick: () -> Unit) {
    val skin = A2g.skin
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.level_label, level.number), style = MaterialTheme.typography.labelMedium, color = skin.inkMuted)
            Text(level.title, style = MaterialTheme.typography.bodyLarge, color = skin.ink)
        }
        when (val s = level.status) {
            LevelStatus.New -> Badge(stringResource(R.string.level_status_new), skin.inkMuted, skin.paper)
            is LevelStatus.InProgress -> Badge(stringResource(R.string.level_status_in_progress, s.position, s.total), skin.paused, skin.paper)
            LevelStatus.NeedsReview -> Badge(stringResource(R.string.home_needs_review), skin.evidence, skin.evidenceSoft)
            LevelStatus.Done -> Badge(stringResource(R.string.level_status_done), skin.correct, skin.correctSoft)
        }
        Spacer(Modifier.size(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = skin.inkMuted)
    }
}

@Composable
private fun ImportCard(onPickPdf: () -> Unit) {
    val skin = A2g.skin
    Card(colors = CardDefaults.cardColors(containerColor = skin.paper), border = CardDefaults.outlinedCardBorder()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleMedium, color = skin.ink)
            Text(stringResource(R.string.import_body), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
            Button(onClick = onPickPdf) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.import_pick_pdf))
            }
        }
    }
}

/** Pick your character. Each one plays live (breathes, blinks, reacts to a poke); the chosen one cheers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(selectedId: String?, onSelect: (String) -> Unit) {
    val skin = A2g.skin
    val current = com.anything2game.app.ui.character.LocalAvatar.current
    androidx.compose.material3.Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = skin.card)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.avatar_pick_title), style = MaterialTheme.typography.titleMedium, color = skin.ink)
            Text(stringResource(R.string.avatar_pick_body), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.anything2game.app.ui.character.Avatars.all.forEach { a ->
                    val chosen = a.id == (selectedId ?: current.id)
                    Column(
                        Modifier
                            .border(if (chosen) 2.dp else 1.dp, if (chosen) skin.action else skin.outline.copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .selectable(chosen, role = androidx.compose.ui.semantics.Role.RadioButton) { onSelect(a.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        // Only the chosen one can be poked; on the others a tap on the picture must choose them.
                        a.Draw(if (chosen) com.anything2game.app.ui.character.Mood.HAPPY else com.anything2game.app.ui.character.Mood.IDLE, Modifier.size(64.dp), pokeable = chosen)
                        Text(stringResource(a.nameRes), style = MaterialTheme.typography.labelLarge, color = if (chosen) skin.action else skin.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(stringResource(a.traitRes), style = MaterialTheme.typography.labelSmall, color = skin.inkMuted)
                    }
                }
            }
        }
    }
}
