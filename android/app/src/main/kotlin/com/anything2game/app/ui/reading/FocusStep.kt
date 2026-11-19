package com.anything2game.app.ui.reading

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.data.tutor.TutorCache
import com.anything2game.app.ui.home.PaperCard
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.tutor.Facet
import com.anything2game.contract.tutor.MAX_FOCUS_NOTE
import com.anything2game.contract.tutor.TutorRules
import com.anything2game.contract.tutor.TutorSession

/**
 * "What do you want to get out of this reading?" — shown once at the start of a session whose questions say which
 * facet they teach. The answer only changes the order of the questions and explanations; nothing is dropped.
 */
@StringRes
internal fun Facet.label(): Int = when (this) {
    Facet.BACKGROUND -> R.string.focus_background
    Facet.METHOD -> R.string.focus_method
    Facet.RESULT -> R.string.focus_result
    Facet.LIMITATION -> R.string.focus_limitation
}

/** Not asked yet, nothing answered yet, and the content actually carries facets (otherwise there is nothing to reorder). */
internal fun focusDue(c: TutorCache?): Boolean =
    c != null && !c.focusChosen && c.session.assessment.isEmpty() && c.draftAnswers.isEmpty() && c.questionIndex == 0 &&
        TutorRules.carriesFacets(c.session.questions)

/**
 * The single hook for the reading screen. Emits the focus question while it is due and returns true (the caller then
 * shows nothing else); afterwards emits a one-line reminder of the choice and returns false.
 */
fun LazyListScope.focusItems(c: TutorCache?, enabled: Boolean, allowNote: Boolean, onSet: (List<Facet>, String) -> Unit): Boolean {
    if (c == null) return false
    if (focusDue(c)) {
        item { FocusPicker(enabled, allowNote, onSet) }
        return true
    }
    val s = c.session
    if (c.focusChosen && (s.focus.isNotEmpty() || s.focusNote.isNotBlank())) item { FocusSummary(s) }
    return false
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FocusPicker(enabled: Boolean, allowNote: Boolean, onSet: (List<Facet>, String) -> Unit) {
    val skin = A2g.skin
    var mask by rememberSaveable { mutableIntStateOf(0) } // bit per Facet ordinal; 0 = let the explainer decide
    var note by rememberSaveable { mutableStateOf("") }
    PaperCard {
        Text(stringResource(R.string.focus_title), style = MaterialTheme.typography.titleLarge, color = skin.ink)
        Text(stringResource(R.string.focus_hint), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mask == 0, onClick = { mask = 0 }, enabled = enabled, modifier = Modifier.testTag("focus_decide"),
                label = { Text(stringResource(R.string.focus_decide)) })
            Facet.entries.forEach { f ->
                FilterChip(selected = mask and (1 shl f.ordinal) != 0, onClick = { mask = mask xor (1 shl f.ordinal) }, enabled = enabled,
                    modifier = Modifier.testTag("focus_${f.name.lowercase()}"), label = { Text(stringResource(f.label())) })
            }
        }
        // Free text can only steer a real model; a pre-written course has nothing to read it with.
        if (allowNote) OutlinedTextField(value = note, onValueChange = { note = it.take(MAX_FOCUS_NOTE) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("focus_note"), minLines = 2, maxLines = 4,
            label = { Text(stringResource(R.string.focus_other)) },
            supportingText = { Text(stringResource(R.string.focus_other_note)) })
        Button(onClick = { onSet(Facet.entries.filter { mask and (1 shl it.ordinal) != 0 }, if (allowNote) note else "") }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("focus_continue")) { Text(stringResource(R.string.focus_continue)) }
    }
}

@Composable
private fun FocusSummary(s: TutorSession) {
    val skin = A2g.skin
    if (s.focus.isNotEmpty()) Text(stringResource(R.string.focus_summary, s.focus.map { stringResource(it.label()) }.joinToString(" · ")),
        style = MaterialTheme.typography.labelLarge, color = skin.action, modifier = Modifier.testTag("focus_summary"))
    if (s.focusNote.isNotBlank()) Text(stringResource(R.string.focus_summary_note, s.focusNote),
        style = MaterialTheme.typography.bodySmall, color = skin.inkMuted, modifier = Modifier.testTag("focus_summary_note"))
}
