package com.anything2game.app.ui.play

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.anything2game.app.R
import com.anything2game.contract.FrameId

/**
 * Everything a screen says or draws about a frame, in one row per frame. Adding a frame is one `when` branch here (the compiler
 * insists on it) and its strings; no screen has its own table.
 */
internal data class FramePresentation(
    /** "Case": the name on the Play-as chips. */
    @StringRes val name: Int,
    @StringRes val description: Int,
    @StringRes val bestFor: Int,
    @StringRes val recapTitle: Int,
    /** The badge on the last, hardest question of a level; null where the frame has no boss. */
    @StringRes val bossLabel: Int?,
    @StringRes val failedTitle: Int,
    /** "Case practice": what a route step of this frame is called. */
    @StringRes val routeLabel: Int,
    val icon: ImageVector,
)

internal fun FrameId.presentation(): FramePresentation = when (this) {
    FrameId.CLASSIC -> FramePresentation(R.string.frame_classic, R.string.frame_classic_desc, R.string.frame_classic_best,
        R.string.recap_title_classic, null, R.string.failed_title_default, R.string.route_kind_classic, Icons.Filled.Checklist)
    FrameId.CASE -> FramePresentation(R.string.frame_case, R.string.frame_case_desc, R.string.frame_case_best,
        R.string.recap_title, R.string.boss_case, R.string.failed_title_case, R.string.route_kind_case, Icons.Filled.Search)
    FrameId.ESCAPE -> FramePresentation(R.string.frame_escape, R.string.frame_escape_desc, R.string.frame_escape_best,
        R.string.recap_title_escape, R.string.boss_escape, R.string.failed_title_escape, R.string.route_kind_escape, Icons.Filled.MeetingRoom)
    FrameId.ROOKIE -> FramePresentation(R.string.frame_rookie, R.string.frame_rookie_desc, R.string.frame_rookie_best,
        R.string.recap_title_rookie, R.string.boss_rookie, R.string.failed_title_default, R.string.route_kind_rookie, Icons.Filled.School)
    FrameId.COMPANION -> FramePresentation(R.string.frame_companion, R.string.frame_companion_desc, R.string.frame_companion_best,
        R.string.recap_title_companion, null, R.string.failed_title_default, R.string.route_kind_companion, Icons.Filled.Forum)
}
