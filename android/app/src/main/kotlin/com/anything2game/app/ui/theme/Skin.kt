package com.anything2game.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A skin = colours by ROLE, not by component. Screens ask for "evidence" or "wrong", never for a hex value,
 * so the visual team can replace the whole look by adding one more [Skin] instance.
 */
@Immutable
data class Skin(
    val paper: Color,        // page background
    val card: Color,         // content surfaces
    val ink: Color,          // primary text
    val inkMuted: Color,     // secondary text
    val outline: Color,
    val action: Color,       // primary buttons
    val onAction: Color,
    val evidence: Color,     // everything about sources: "Show evidence", highlighted quotes
    val evidenceSoft: Color,
    val correct: Color,
    val correctSoft: Color,
    val wrong: Color,
    val wrongSoft: Color,
    val hint: Color,
    val hintSoft: Color,
    val paused: Color,
    val headingFont: FontFamily,
)

/** Default "case file" skin. Placeholder until the visual team ships theirs. */
val CaseFileSkin = Skin(
    paper = Color(0xFFF6F1E7),
    card = Color(0xFFFFFCF5),
    ink = Color(0xFF1F2A37),
    inkMuted = Color(0xFF5B6575),
    outline = Color(0xFFD9D0BF),
    action = Color(0xFF1F4E5F),
    onAction = Color.White,
    evidence = Color(0xFFB7791F),
    evidenceSoft = Color(0xFFF8E7C4),
    correct = Color(0xFF2F7D4F),
    correctSoft = Color(0xFFDDF0E3),
    wrong = Color(0xFFB4442F),
    wrongSoft = Color(0xFFF7DED7),
    hint = Color(0xFF6B4FA0),
    hintSoft = Color(0xFFEAE3F5),
    paused = Color(0xFF2B5C9E),
    headingFont = FontFamily.Serif,
)

val LocalSkin = staticCompositionLocalOf { CaseFileSkin }

object A2g {
    val skin: Skin @Composable get() = LocalSkin.current
}

/** Each cast role keeps one colour everywhere (name tag, bubble edge, avatar). */
fun Skin.castColor(role: com.anything2game.contract.CastRole): Color = when (role) {
    com.anything2game.contract.CastRole.PARTNER -> action
    com.anything2game.contract.CastRole.SUSPECT -> wrong
    com.anything2game.contract.CastRole.ROOKIE -> hint
    com.anything2game.contract.CastRole.COMPANION -> correct
    com.anything2game.contract.CastRole.NARRATOR -> evidence
}

@Composable
fun A2gTheme(skin: Skin = CaseFileSkin, content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = skin.action,
        onPrimary = skin.onAction,
        background = skin.paper,
        onBackground = skin.ink,
        surface = skin.card,
        onSurface = skin.ink,
        onSurfaceVariant = skin.inkMuted,
        outline = skin.outline,
        secondary = skin.evidence,
        error = skin.wrong,
    )
    val base = Typography()
    val typography = base.copy(
        headlineSmall = TextStyle(fontFamily = skin.headingFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = TextStyle(fontFamily = skin.headingFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
    )
    CompositionLocalProvider(LocalSkin provides skin) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
