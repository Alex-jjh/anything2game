package com.anything2game.app.ui.character

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class BlobShape { ROUND, DROP, CLOUD }
enum class Topper { NONE, LEAF, ANTENNA }

/** Everything that makes one blob look different from another. Original placeholder characters, not anyone's IP. */
data class BlobLook(
    val body: Color,
    val outline: Color,
    val cheek: Color,
    val accent: Color,
    val shape: BlobShape,
    val topper: Topper,
    val ink: Color = Color(0xFF2A1F1A),
    val mouth: Color = Color(0xFF7A2E2A),
    val tongue: Color = Color(0xFFE88A8A),
)

/** Procedural character: drawn in code from simple shapes, so every pose is free and it reacts frame by frame. */
class BlobAvatar(
    override val id: String,
    @StringRes override val nameRes: Int,
    @StringRes override val traitRes: Int,
    val look: BlobLook,
    private val lines: Map<Mood, Int>,
) : AvatarCharacter {
    @ArrayRes override fun linesFor(mood: Mood): Int? = lines[mood]

    @Composable
    override fun Draw(mood: Mood, modifier: Modifier, lookAt: Offset, useTilt: Boolean) {
        val motion = rememberAvatarMotion(mood, lookAt, useTilt)
        val measurer = rememberTextMeasurer()
        Canvas(modifier.aspectRatio(1f).then(motion.pokeModifier)) {
            // Lean tilts the whole body from the feet, like leaning with the card.
            rotate(motion.lean * 16f, Offset(size.width / 2, size.height * 0.94f)) { drawBlob(look, motion, measurer) }
        }
    }
}

private fun DrawScope.drawBlob(look: BlobLook, m: AvatarMotion, measurer: TextMeasurer) {
    val mood = m.mood
    val s = size.minDimension
    val twoPi = (2 * PI).toFloat()
    val tremble = if (m.charge > 0.05f) sin(m.beat * twoPi * 4) * s * 0.008f * m.charge else 0f
    val celebrateHop = if (mood == Mood.CELEBRATE) -abs(sin(m.beat * PI.toFloat())) * 0.07f else 0f
    val sx = 1f + m.squash + m.charge * 0.10f - m.breath * 0.01f
    val sy = 1f - m.squash + m.charge * 0.10f + m.breath * 0.025f
    val w = s * 0.72f * sx
    val h = s * 0.62f * sy
    val ground = s * 0.94f
    val bottom = ground + (m.hop + celebrateHop) * s
    val cx = s / 2 + m.lean * s * 0.06f + tremble
    val topX = cx + m.lean * w * 0.10f
    val top = bottom - h
    val sw = s * 0.022f

    // Shadow shrinks as the character leaves the ground.
    val lift = ((ground - bottom) / s).coerceIn(0f, 0.3f)
    val shW = w * 0.8f * (1f - lift * 1.6f)
    drawOval(Color.Black.copy(alpha = 0.10f), Offset(s / 2 - shW / 2, ground - s * 0.022f), Size(shW, s * 0.044f))

    // Feet behind the body.
    val footW = w * 0.22f; val footH = h * 0.13f
    listOf(-1f, 1f).forEach { side ->
        val fx = cx + side * w * 0.2f - footW / 2
        drawOval(look.outline, Offset(fx - sw, bottom - footH * 0.6f - sw), Size(footW + 2 * sw, footH + 2 * sw))
        drawOval(look.body, Offset(fx, bottom - footH * 0.6f), Size(footW, footH))
    }

    // Body (+ cloud bumps). Strokes first, fills second: only the outer outline of the union stays visible.
    val parts = buildList {
        add(bodyPath(cx, topX, top, bottom, w, h, if (look.shape == BlobShape.DROP) 0.16f else 0f, look.shape == BlobShape.DROP))
        if (look.shape == BlobShape.CLOUD) {
            add(circle(topX - w * 0.24f, top + h * 0.16f, w * 0.17f))
            add(circle(topX + w * 0.06f, top + h * 0.04f, w * 0.2f))
            add(circle(topX + w * 0.30f, top + h * 0.2f, w * 0.14f))
        }
    }
    parts.forEach { drawPath(it, look.outline, style = Stroke(sw * 2)) }
    parts.forEach { drawPath(it, look.body) }
    rotate(-28f, Offset(cx - w * 0.24f, top + h * 0.2f)) {
        drawOval(Color.White.copy(alpha = 0.38f), Offset(cx - w * 0.33f, top + h * 0.15f), Size(w * 0.18f, h * 0.09f))
    }

    // Topper with secondary motion: it lags behind the lean.
    val drag = (m.lean - m.lag)
    when (look.topper) {
        Topper.LEAF -> {
            val base = Offset(topX, top + h * 0.02f)
            rotate(-24f - drag * 40f + m.breath * 6f, base) {
                val leaf = Path().apply {
                    moveTo(base.x, base.y)
                    quadraticTo(base.x + s * 0.02f, base.y - s * 0.13f, base.x + s * 0.13f, base.y - s * 0.12f)
                    quadraticTo(base.x + s * 0.10f, base.y - s * 0.02f, base.x, base.y)
                    close()
                }
                drawPath(leaf, look.outline, style = Stroke(sw * 1.6f))
                drawPath(leaf, look.accent)
                drawLine(look.outline, base, Offset(base.x + s * 0.09f, base.y - s * 0.09f), sw * 0.6f, StrokeCap.Round)
            }
        }
        Topper.ANTENNA -> {
            val base = Offset(topX, top + sw)
            val tip = Offset(base.x - drag * s * 0.35f + m.lean * s * 0.03f, top - s * 0.13f + m.breath * s * 0.01f)
            drawLine(look.outline, base, tip, sw * 1.1f, StrokeCap.Round)
            drawCircle(look.outline, s * 0.045f + sw, tip)
            drawCircle(look.accent, s * 0.045f, tip)
        }
        Topper.NONE -> Unit
    }

    // Face.
    val faceCx = cx + m.lean * w * 0.07f
    val eyeY = top + h * 0.46f
    val ex = w * 0.17f
    val er = s * 0.048f
    val cheekA = if (mood in setOf(Mood.HAPPY, Mood.CELEBRATE, Mood.CHARGING, Mood.OBJECT)) 0.85f else 0.5f
    listOf(-1f, 1f).forEach { side ->
        drawOval(look.cheek.copy(alpha = cheekA), Offset(faceCx + side * (ex + er * 1.25f) - er * 0.8f, eyeY + er * 1.1f), Size(er * 1.6f, er * 0.9f))
    }
    listOf(-1f, 1f).forEach { side -> drawEye(look, m, Offset(faceCx + side * ex, eyeY), er, side) }
    drawBrows(look, mood, faceCx, eyeY, ex, er)
    drawMouth(look, mood, faceCx, eyeY + er * 2.1f, w * 0.10f, er)

    // Arms in front: little stubs whose angle carries the pose.
    val (la, ra) = armAngles(mood, m.beat)
    val armW = w * 0.2f; val armH = h * 0.13f
    drawArm(look, Offset(cx - w * 0.44f, bottom - h * 0.42f), la, armW, armH, sw)
    drawArm(look, Offset(cx + w * 0.44f, bottom - h * 0.42f), ra, if (mood == Mood.OBJECT) armW * 1.3f else armW, armH, sw)

    // Effects.
    val fx = TextStyle(fontWeight = FontWeight.Black, fontSize = (s * 0.17f).toSp())
    when (mood) {
        Mood.OBJECT -> {
            val o = Offset((cx + w * 0.5f).coerceAtMost(s * 0.82f), bottom - h * 0.62f)
            repeat(3) { i ->
                val a = (-60f + i * 30f) * (PI / 180).toFloat()
                drawLine(Color(0xFFB3261E), Offset(o.x + cos(a) * s * 0.06f, o.y + sin(a) * s * 0.06f), Offset(o.x + cos(a) * s * 0.11f, o.y + sin(a) * s * 0.11f), sw * 1.1f, StrokeCap.Round)
            }
            drawText(measurer, "!", Offset(cx - w * 0.62f, top - s * 0.12f), fx.copy(color = Color(0xFFB3261E)))
        }
        Mood.SLEEPY -> {
            drawText(measurer, "z", Offset(cx + w * 0.42f, top - s * 0.02f - m.breath * s * 0.03f), fx.copy(color = look.outline, fontSize = (s * 0.11f).toSp()))
            drawText(measurer, "Z", Offset(cx + w * 0.55f, top - s * 0.16f - m.breath * s * 0.03f), fx.copy(color = look.outline, fontSize = (s * 0.15f).toSp()))
        }
        Mood.THINKING, Mood.DOUBT -> drawText(measurer, "?", Offset(cx + w * 0.46f, top - s * 0.10f), fx.copy(color = look.outline))
        Mood.SURPRISED -> drawText(measurer, "!", Offset(cx + w * 0.48f, top - s * 0.10f), fx.copy(color = look.outline))
        Mood.CELEBRATE -> listOf(Offset(0.08f, 0.22f), Offset(0.9f, 0.14f), Offset(0.04f, 0.62f), Offset(0.95f, 0.55f)).forEachIndexed { i, p ->
            // Sparkles twinkle around the character, never over the face.
            val r = s * (0.035f + 0.02f * sin(m.beat * twoPi + i * 1.7f))
            sparkle(Offset(p.x * s, p.y * s), r, if (i % 2 == 0) look.accent else Color(0xFFE0A526))
        }
        else -> Unit
    }
}

private fun bodyPath(cx: Float, topX: Float, top: Float, bottom: Float, w: Float, h: Float, n: Float, pointy: Boolean) = Path().apply {
    val l = cx - w / 2; val r = cx + w / 2
    val tipY = if (pointy) top - h * 0.06f else top
    moveTo(cx, bottom)
    cubicTo(r - w * 0.05f, bottom, r, bottom - h * 0.12f, r, bottom - h * 0.42f)
    cubicTo(r - w * n, top + h * 0.14f, topX + w * (0.30f - n * 1.4f), tipY, topX, tipY)
    cubicTo(topX - w * (0.30f - n * 1.4f), tipY, l + w * n, top + h * 0.14f, l, bottom - h * 0.42f)
    cubicTo(l, bottom - h * 0.12f, l + w * 0.05f, bottom, cx, bottom)
    close()
}

private fun circle(x: Float, y: Float, r: Float) = Path().apply { addOval(androidx.compose.ui.geometry.Rect(x - r, y - r, x + r, y + r)) }

private fun DrawScope.drawEye(look: BlobLook, m: AvatarMotion, c: Offset, er: Float, side: Float) {
    val line = Stroke(er * 0.42f, cap = StrokeCap.Round)
    when (m.mood) {
        Mood.HAPPY, Mood.CELEBRATE -> drawArc(look.ink, 200f, 140f, false, Offset(c.x - er, c.y - er * 0.7f), Size(er * 2, er * 1.6f), style = line)
        Mood.SLEEPY -> drawArc(look.ink, 20f, 140f, false, Offset(c.x - er, c.y - er * 0.9f), Size(er * 2, er * 1.4f), style = line)
        Mood.DEFLATED -> {
            drawArc(look.ink, 0f, 180f, true, Offset(c.x - er * 0.85f, c.y - er * 0.8f), Size(er * 1.7f, er * 1.9f))
            drawLine(look.ink, Offset(c.x - er * 1.05f, c.y + er * 0.12f - side * er * 0.12f), Offset(c.x + er * 1.05f, c.y + er * 0.12f + side * er * 0.12f), er * 0.32f, StrokeCap.Round)
        }
        Mood.SURPRISED -> {
            drawOval(look.ink, Offset(c.x - er * 1.1f, c.y - er * 1.35f), Size(er * 2.2f, er * 2.7f))
            drawCircle(Color.White, er * 0.38f, Offset(c.x - er * 0.35f, c.y - er * 0.6f))
        }
        else -> {
            val squint = if (m.mood == Mood.DOUBT && side > 0) 0.45f else 1f
            val hh = er * 2.3f * squint * (1f - m.blink * 0.88f)
            val p = Offset(c.x + m.look.x * er * 0.35f, c.y + m.look.y * er * 0.35f)
            drawOval(look.ink, Offset(p.x - er * 0.9f, p.y - hh / 2), Size(er * 1.8f, hh))
            if (m.blink < 0.5f && squint == 1f) drawCircle(Color.White, er * 0.36f, Offset(p.x - er * 0.3f + m.look.x * er * 0.2f, p.y - hh * 0.22f))
        }
    }
}

private fun DrawScope.drawBrows(look: BlobLook, mood: Mood, cx: Float, eyeY: Float, ex: Float, er: Float) {
    val wgt = er * 0.38f
    when (mood) {
        Mood.OBJECT, Mood.CHARGING -> listOf(-1f, 1f).forEach { side ->
            val outer = Offset(cx + side * (ex + er * 1.1f), eyeY - er * 2.0f)
            val inner = Offset(cx + side * (ex - er * 0.9f), eyeY - er * 1.35f)
            drawLine(look.ink, outer, inner, wgt, StrokeCap.Round)
        }
        Mood.DOUBT -> {
            drawArc(look.ink, 200f, 140f, false, Offset(cx - ex - er * 1.1f, eyeY - er * 2.6f), Size(er * 2.2f, er * 1.2f), style = Stroke(wgt, cap = StrokeCap.Round))
            drawLine(look.ink, Offset(cx + ex - er, eyeY - er * 1.25f), Offset(cx + ex + er, eyeY - er * 1.45f), wgt, StrokeCap.Round)
        }
        Mood.DEFLATED -> listOf(-1f, 1f).forEach { side ->
            drawLine(look.ink, Offset(cx + side * (ex - er * 0.8f), eyeY - er * 1.9f), Offset(cx + side * (ex + er * 1.0f), eyeY - er * 1.45f), wgt * 0.8f, StrokeCap.Round)
        }
        else -> Unit
    }
}

private fun DrawScope.drawMouth(look: BlobLook, mood: Mood, cx: Float, y: Float, mw: Float, er: Float) {
    val line = Stroke(er * 0.36f, cap = StrokeCap.Round)
    when (mood) {
        Mood.HAPPY, Mood.CELEBRATE -> {
            drawArc(look.mouth, 0f, 180f, true, Offset(cx - mw * 0.62f, y - mw * 0.42f), Size(mw * 1.24f, mw * 1.0f))
            drawOval(look.tongue, Offset(cx - mw * 0.26f, y + mw * 0.12f), Size(mw * 0.52f, mw * 0.32f))
        }
        Mood.OBJECT -> {
            drawOval(look.mouth, Offset(cx - mw * 0.55f, y - mw * 0.32f), Size(mw * 1.1f, mw * 0.9f))
            drawOval(look.tongue, Offset(cx - mw * 0.28f, y + mw * 0.22f), Size(mw * 0.56f, mw * 0.3f))
        }
        Mood.SURPRISED -> drawOval(look.mouth, Offset(cx - mw * 0.24f, y - mw * 0.2f), Size(mw * 0.48f, mw * 0.56f))
        Mood.SLEEPY -> drawOval(look.mouth, Offset(cx - mw * 0.14f, y - mw * 0.1f), Size(mw * 0.28f, mw * 0.32f))
        Mood.DEFLATED -> drawArc(look.ink, 200f, 140f, false, Offset(cx - mw * 0.4f, y - mw * 0.05f), Size(mw * 0.8f, mw * 0.5f), style = line)
        Mood.THINKING -> drawLine(look.ink, Offset(cx - mw * 0.05f, y), Offset(cx + mw * 0.4f, y - mw * 0.12f), er * 0.36f, StrokeCap.Round)
        Mood.DOUBT -> drawLine(look.ink, Offset(cx - mw * 0.4f, y + mw * 0.06f), Offset(cx + mw * 0.4f, y - mw * 0.12f), er * 0.36f, StrokeCap.Round)
        Mood.CHARGING -> drawLine(look.ink, Offset(cx - mw * 0.42f, y), Offset(cx + mw * 0.42f, y), er * 0.5f, StrokeCap.Round)
        else -> drawArc(look.ink, 20f, 140f, false, Offset(cx - mw * 0.5f, y - mw * 0.35f), Size(mw, mw * 0.6f), style = line)
    }
}

/** Arm angles in degrees (0 = pointing right, 90 = down). */
private fun armAngles(mood: Mood, beat: Float): Pair<Float, Float> {
    val wave = sin(beat * 2 * PI.toFloat()) * 12f
    return when (mood) {
        Mood.HAPPY -> 215f to -35f
        Mood.CELEBRATE -> (215f + wave) to (-35f - wave)
        Mood.OBJECT -> 140f to -12f
        Mood.THINKING, Mood.DOUBT -> 125f to 228f
        Mood.CHARGING -> 200f to -20f
        Mood.SURPRISED -> 205f to -25f
        Mood.DEFLATED, Mood.SLEEPY -> 108f to 72f
        Mood.IDLE -> 128f to 52f
    }
}

private fun DrawScope.drawArm(look: BlobLook, shoulder: Offset, deg: Float, aw: Float, ah: Float, sw: Float) {
    rotate(deg, shoulder) {
        drawOval(look.outline, Offset(shoulder.x - ah * 0.3f - sw, shoulder.y - ah / 2 - sw), Size(aw + 2 * sw, ah + 2 * sw))
        drawOval(look.body, Offset(shoulder.x - ah * 0.3f, shoulder.y - ah / 2), Size(aw, ah))
    }
}

private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - r); quadraticTo(c.x, c.y, c.x + r, c.y); quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y); quadraticTo(c.x, c.y, c.x, c.y - r); close()
    }
    drawPath(p, color)
}
