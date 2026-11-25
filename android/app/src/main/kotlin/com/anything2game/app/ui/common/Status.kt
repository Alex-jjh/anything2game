package com.anything2game.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.engine.progress.ActivityStatus
import com.anything2game.engine.progress.Standing

/** The one word for each state of an activity. Every screen that names a state (route, practice list, Me) takes it from here. */
@StringRes
fun Standing.labelRes(): Int = when (this) {
    Standing.NEW -> R.string.status_new
    Standing.NEEDS_PRACTICE -> R.string.status_needs_practice
    Standing.DONE -> R.string.status_done
    Standing.DUE -> R.string.status_due
    Standing.LEARNED -> R.string.status_learned
}

@Composable
fun Standing.label(): String = stringResource(labelRes())

/** "Done", or "In progress · 2/3" while a saved session of the activity is open. */
@Composable
fun ActivityStatus.label(): String =
    if (position != null && total != null) stringResource(R.string.status_in_progress, position!!, total!!) else standing.label()

/** The badge that shows an [ActivityStatus]; the same look wherever the state appears. */
@Composable
fun StatusBadge(status: ActivityStatus, modifier: Modifier = Modifier) {
    val skin = A2g.skin
    val (ink, fill) = when {
        status.inProgress -> skin.paused to skin.paper
        else -> when (status.standing) {
            Standing.NEW -> skin.inkMuted to skin.paper
            Standing.NEEDS_PRACTICE -> skin.evidence to skin.evidenceSoft
            Standing.DONE -> skin.correct to skin.paper
            Standing.DUE -> skin.hint to skin.hintSoft
            Standing.LEARNED -> skin.correct to skin.correctSoft
        }
    }
    Badge(status.label(), ink, fill, modifier)
}
