package com.anything2game.app.ui.reading

import androidx.annotation.StringRes
import com.anything2game.app.R
import com.anything2game.contract.tutor.TutorMode
import com.anything2game.contract.tutor.Understanding

/** The badge that says how a lesson was made. */
@StringRes
internal fun TutorMode.badgeRes(): Int = when (this) {
    TutorMode.LOCAL_MOCK -> R.string.badge_pre_generated
    TutorMode.MOCK -> R.string.tutor_server_mock_badge
    TutorMode.MODEL -> R.string.tutor_model_badge
}

/** The line under a lesson's title: what the reader may rely on. */
@StringRes
internal fun TutorMode.coverageNoteRes(): Int = if (scripted) R.string.tutor_scripted_note else R.string.tutor_coverage_note

/** The line under the question box: whether the answer comes from a model or from the lesson's own script. */
@StringRes
internal fun TutorMode.askNoteRes(): Int = if (this == TutorMode.MODEL) R.string.tutor_model_note else R.string.tutor_script_note

/** Only a model can use a free-text note about what the reader wants out of the text. */
internal val TutorMode.takesFocusNote get() = this == TutorMode.MODEL

/** What the reader is told about how well an answer showed they understood. One wording wherever it is shown. */
@StringRes
internal fun Understanding.labelRes(): Int = when (this) {
    Understanding.DEMONSTRATED -> R.string.tutor_correct
    Understanding.NEEDS_CLARIFICATION -> R.string.tutor_clarify
    Understanding.NOT_YET_ASSESSED, Understanding.NOT_ASKED -> R.string.tutor_not_assessed
}
