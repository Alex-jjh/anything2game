package com.anything2game.app.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.anything2game.contract.game.AllocationConfig
import com.anything2game.contract.game.GameSpec
import com.anything2game.contract.game.PartitionConfig
import com.anything2game.contract.game.icon
import com.anything2game.contract.game.icons

private val ink = Color(0xFF2A2630)

/** Sky, ground and mood per game, from its main icon, so the tiles on the Playground are told apart at a glance. */
internal fun sceneColours(icon: String): Pair<Color, Color> = when (icon) {
    "locker" -> Color(0xFFDCE8F2) to Color(0xFFB6C9DB)
    "commit" -> Color(0xFFE0F0DE) to Color(0xFFB5D6B0)
    "page" -> Color(0xFFF6EBD3) to Color(0xFFDDC79A)
    "layer", "layers" -> Color(0xFFE9E2F5) to Color(0xFFC7B9E3)
    "number" -> Color(0xFFFBE9B8) to Color(0xFFE5C676)
    "wine", "cloth" -> Color(0xFFF6DDE6) to Color(0xFFDDB0C2)
    "scroll", "book" -> Color(0xFFFFF0C9) to Color(0xFFE8CF8C)
    "clock", "chip" -> Color(0xFFDCEBF5) to Color(0xFFB0CBDE)
    "wheat", "corn" -> Color(0xFFF1EDC3) to Color(0xFFD3C97F)
    else -> Color(0xFFF8E7C4) to Color(0xFFDDC08A)
}

/** The mini-scene on a game tile: partition = a row of themed tiles with the ruled-out ones greyed; allocation = two zones and a token between. */
@Composable
internal fun GameIllustration(spec: GameSpec, modifier: Modifier = Modifier) {
    val config = spec.stages.first().config
    Canvas(modifier.fillMaxWidth().aspectRatio(1.9f)) {
        when (config) {
            is PartitionConfig -> drawPartitionScene(config.icon())
            is AllocationConfig -> config.icons().let { drawAllocationScene(it.first, it.second, it.token) }
        }
    }
}

private fun DrawScope.backdrop(icon: String): Float {
    val (sky, ground) = sceneColours(icon)
    drawRect(sky, Offset.Zero, size)
    val groundTop = size.height * 0.78f
    drawRect(ground, Offset(0f, groundTop), Size(size.width, size.height - groundTop))
    drawRect(ink.copy(alpha = 0.55f), Offset(0f, groundTop), Size(size.width, size.height * 0.02f))
    // 8-bit dotting on the ground
    val d = size.height * 0.03f
    var x = d * 2
    var row = 0
    while (x < size.width) { drawRect(ink.copy(alpha = 0.12f), Offset(x, groundTop + d * (3 + (row % 3) * 2)), Size(d, d)); x += d * 5; row++ }
    return groundTop
}

private fun DrawScope.drawPartitionScene(icon: String) {
    val groundTop = backdrop(icon)
    val tiles = 5
    val tile = size.width * 0.15f
    val gap = (size.width - tiles * tile) / (tiles + 1)
    val y = groundTop - tile - size.height * 0.04f
    val sprite = Sprites.of(icon)
    val pixel = tile * 0.62f / maxOf(sprite.width, sprite.height)
    for (i in 0 until tiles) {
        val x = gap + i * (tile + gap)
        val out = i < 2
        val target = i == 3
        val fill = when { out -> Color(0xFFEDE8DA); target -> Color(0xFFFFE9A6); else -> Color(0xFFFFFCF5) }
        drawRect(ink.copy(alpha = 0.8f), Offset(x + 3f, y + 4f), Size(tile, tile))
        drawRect(fill, Offset(x, y), Size(tile, tile))
        drawRect(if (out) ink.copy(alpha = 0.35f) else ink, Offset(x, y), Size(tile, tile), style = Stroke(size.width * 0.008f + 2f))
        drawSprite(sprite, Offset(x + (tile - pixel * sprite.width) / 2f, y + (tile - pixel * sprite.height) / 2f), pixel, dim = out, alpha = if (out) 0.6f else 1f)
        if (target) drawSprite(Sprites.sparkle, Offset(x + tile * 0.62f, y - tile * 0.28f), tile * 0.034f)
    }
    // the cut between the greyed and the live tiles
    val cutX = gap + 2 * (tile + gap) - gap / 2f
    drawRect(Color(0xFFB9861A), Offset(cutX - 3f, y - tile * 0.18f), Size(6f, tile * 1.36f))
    for (h in 0 until 3) drawSprite(Sprites.heart, Offset(size.width * 0.04f + h * size.width * 0.07f, size.height * 0.07f), size.width * 0.0075f, dim = h == 2)
}

private fun DrawScope.drawAllocationScene(first: String, second: String, token: String) {
    val groundTop = backdrop(first)
    val firstSprite = Sprites.of(first); val secondSprite = Sprites.of(second)
    val big = size.height * 0.46f
    fun place(sprite: Sprite, cx: Float) {
        val pixel = big / maxOf(sprite.width, sprite.height)
        val platform = Size(big * 1.5f, size.height * 0.05f)
        drawRect(ink.copy(alpha = 0.8f), Offset(cx - platform.width / 2f + 3f, groundTop - platform.height + 4f), platform)
        drawRect(Color(0xFFFFFCF5), Offset(cx - platform.width / 2f, groundTop - platform.height - 1f), platform)
        drawRect(ink, Offset(cx - platform.width / 2f, groundTop - platform.height - 1f), platform, style = Stroke(3f))
        drawSprite(sprite, Offset(cx - pixel * sprite.width / 2f, groundTop - platform.height - 4f - pixel * sprite.height), pixel)
    }
    place(firstSprite, size.width * 0.27f); place(secondSprite, size.width * 0.73f)
    val chip = Sprites.of(token)
    val cp = size.height * 0.16f / maxOf(chip.width, chip.height)
    drawSprite(chip, Offset(size.width / 2f - cp * chip.width / 2f, size.height * 0.2f), cp)
    // arrows from the token to both zones
    val arrow = size.height * 0.026f
    for (dir in listOf(-1, 1)) for (k in 0 until 4) drawRect(ink.copy(alpha = 0.65f),
        Offset(size.width / 2f + dir * (size.width * 0.07f + k * arrow * 2.6f), size.height * 0.27f + k * arrow * 1.2f), Size(arrow * 1.5f, arrow * 1.5f))
}
