package com.anything2game.app.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.anything2game.contract.CastRole

// Three candidate directions from docs/design/research/visual-identity-and-a11y.md §5 (tokens and contrast computed there).
// PREVIEW ONLY for the team's choice: system fonts stand in for the proposed families; devices (tag/lamp/stamp) not drawn yet.

/** Lab Tag: cool paper, cobalt action, UV-lime evidence highlighter, clipped-corner tags, mono labels. */
val LabTagSkin = Skin(
    paper = Color(0xFFE9EEF1), card = Color(0xFFFAFCFD), ink = Color(0xFF0E1822), inkMuted = Color(0xFF46586A), outline = Color(0xFF66788A),
    action = Color(0xFF2037C2), onAction = Color.White,
    evidence = Color(0xFF5A6B00), evidenceSoft = Color(0xFFCCEF3A),
    correct = Color(0xFF00697A), correctSoft = Color(0xFFD3EEF2), wrong = Color(0xFFB93A0A), wrongSoft = Color(0xFFFBE0D3),
    hint = Color(0xFF6A3FB5), hintSoft = Color(0xFFE7DEF7), paused = Color(0xFF4B5B6B),
    headingFont = FontFamily.SansSerif, bodyFont = FontFamily.SansSerif, cardShape = CutCornerShape(4.dp),
    cast = mapOf(CastRole.PARTNER to Color(0xFF2037C2), CastRole.SUSPECT to Color(0xFF5B3A8C), CastRole.ROOKIE to Color(0xFF8A5A00),
        CastRole.COMPANION to Color(0xFF9A3F6B), CastRole.NARRATOR to Color(0xFF46586A)),
    displayName = "Lab Tag",
)

/** Night Desk (dark-first): lamp-lit evidence on a dark desk, soft large radii. */
val NightDeskSkin = Skin(
    paper = Color(0xFF16120E), card = Color(0xFF221C16), ink = Color(0xFFF1E9DA), inkMuted = Color(0xFFB3A793), outline = Color(0xFF7B6D58),
    action = Color(0xFFF0B03A), onAction = Color(0xFF1C1305),
    evidence = Color(0xFFFFD98A), evidenceSoft = Color(0xFF3A2C12),
    correct = Color(0xFF62D6C0), correctSoft = Color(0xFF12362F), wrong = Color(0xFFFF8F73), wrongSoft = Color(0xFF4A1F14),
    hint = Color(0xFFBFA8FF), hintSoft = Color(0xFF2D2450), paused = Color(0xFF8FB8E8),
    headingFont = FontFamily.SansSerif, bodyFont = FontFamily.SansSerif, cardShape = RoundedCornerShape(24.dp), dark = true,
    cast = mapOf(CastRole.PARTNER to Color(0xFFE8C27A), CastRole.SUSPECT to Color(0xFFC9A0DC), CastRole.ROOKIE to Color(0xFF9FD3E6),
        CastRole.COMPANION to Color(0xFFE6A3B8), CastRole.NARRATOR to Color(0xFFB3A793)),
    displayName = "Night Desk",
)

/** Cyanotype: one ink blue, vermilion stamp verdicts, yellow evidence, hard square shapes. */
val CyanotypeSkin = Skin(
    paper = Color(0xFFF1F3F6), card = Color.White, ink = Color(0xFF0B2A6F), inkMuted = Color(0xFF41578F), outline = Color(0xFF0B2A6F),
    action = Color(0xFF0B2A6F), onAction = Color.White,
    evidence = Color(0xFF7A5A00), evidenceSoft = Color(0xFFFFC62B),
    correct = Color(0xFF0B2A6F), correctSoft = Color(0xFFDCE6FA), wrong = Color(0xFFBA2A0A), wrongSoft = Color(0xFFFBDDD5),
    hint = Color(0xFF5B3FA8), hintSoft = Color(0xFFE8E1F6), paused = Color(0xFF5B6B88),
    headingFont = FontFamily.SansSerif, bodyFont = FontFamily.SansSerif, cardShape = RoundedCornerShape(2.dp),
    cast = mapOf(CastRole.PARTNER to Color(0xFF0B2A6F), CastRole.SUSPECT to Color(0xFF6B4A00), CastRole.ROOKIE to Color(0xFF2E6B8F),
        CastRole.COMPANION to Color(0xFF7A3E6B), CastRole.NARRATOR to Color(0xFF41578F)),
    displayName = "Cyanotype",
)

val AllSkins = listOf(CaseFileSkin, LabTagSkin, NightDeskSkin, CyanotypeSkin)
