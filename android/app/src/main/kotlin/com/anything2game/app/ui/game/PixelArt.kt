package com.anything2game.app.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The games' 8-bit accent: small hand-built sprites drawn pixel by pixel. One shared palette keeps them consistent with the
 * paper / case-file look (warm paper, ink outlines), and a sprite is plain data so a new icon is a few lines.
 */
internal class Sprite(val width: Int, val height: Int, private val pixels: Array<Char>) {
    fun at(x: Int, y: Int): Char = pixels[y * width + x]
}

/** A small drawing board: rectangles and dots on a transparent grid, finished into an immutable [Sprite]. */
private class Grid(val w: Int, val h: Int) {
    private val cells = Array(w * h) { '.' }
    fun dot(x: Int, y: Int, c: Char) { if (x in 0 until w && y in 0 until h) cells[y * w + x] = c }
    fun rect(x: Int, y: Int, rw: Int, rh: Int, c: Char) { for (j in y until y + rh) for (i in x until x + rw) dot(i, j, c) }
    /** An outlined box with its four corner pixels cut off: the 8-bit rounded rectangle. */
    fun frame(x: Int, y: Int, rw: Int, rh: Int, fill: Char, outline: Char = 'k') {
        rect(x, y, rw, rh, outline); rect(x + 1, y + 1, rw - 2, rh - 2, fill)
        for ((cx, cy) in listOf(x to y, x + rw - 1 to y, x to y + rh - 1, x + rw - 1 to y + rh - 1)) dot(cx, cy, '.')
    }
    fun rows(top: Int, lines: List<String>) { lines.forEachIndexed { j, line -> line.forEachIndexed { i, c -> if (c != '.') dot(i, top + j, c) } } }
    fun build() = Sprite(w, h, cells)
}

private fun literal(lines: List<String>): Sprite {
    val w = lines.maxOf { it.length }
    return Grid(w, lines.size).also { it.rows(0, lines.map { l -> l.padEnd(w, '.') }) }.build()
}

private fun grid(w: Int = 12, h: Int = 12, draw: Grid.() -> Unit) = Grid(w, h).apply(draw).build()

/** One colour per letter. 'k' is the shared ink outline; the rest are warm, slightly muted so sprites sit on the paper skin. */
private val palette = mapOf(
    'k' to Color(0xFF2A2630), 'w' to Color(0xFFFFFCF5), 's' to Color(0xFFD9D2C0), 'g' to Color(0xFF8E877A),
    'r' to Color(0xFFD2452D), 'R' to Color(0xFF8F2A1B), 'o' to Color(0xFFE8892B), 'y' to Color(0xFFF2C43D), 'Y' to Color(0xFFB9861A),
    'n' to Color(0xFF9A6A38), 'N' to Color(0xFF5B3A1A), 't' to Color(0xFFD8B27A),
    'e' to Color(0xFF3E9A5A), 'E' to Color(0xFF25683B), 'l' to Color(0xFF9ED58B),
    'b' to Color(0xFF2F6FA3), 'B' to Color(0xFF1E4468), 'c' to Color(0xFF7CC6E0),
    'p' to Color(0xFF7B5BB5), 'P' to Color(0xFFE58FB0),
)

internal object Sprites {
    val heart = literal(listOf(".kkk.kkk.", "krrrkrrrk", "krwrrrrrk", "krrrrrrrk", ".krrrrrk.", "..krrrk..", "...krk...", "....k...."))
    val star = literal(listOf("....k....", "...kyk...", "..kyyyk..", "kkkyyykkk", "kyyyyyyyk", ".kyyyyyk.", "..kyyyk..", ".kyykyyk.", ".kkk.kkk."))
    val sparkle = literal(listOf("...y...", "...y...", "..yyy..", "yyywyyy", "..yyy..", "...y...", "...y..."))

    private val locker = grid { frame(1, 0, 10, 12, 'b'); rect(3, 2, 6, 1, 'B'); rect(3, 4, 6, 1, 'B'); rect(3, 6, 6, 1, 'B')
        rect(3, 8, 3, 1, 'c'); rect(8, 8, 1, 2, 'y'); rect(3, 10, 6, 1, 'B') }
    private val commit = grid { rect(5, 0, 2, 12, 'g'); frame(2, 3, 8, 6, 'e'); rect(3, 4, 2, 1, 'l') }
    private val page = grid { frame(2, 0, 8, 12, 'w'); for (y in intArrayOf(3, 5, 7, 9)) rect(4, y, 4, 1, 'g'); rect(4, 2, 2, 1, 'r') }
    private val layer = grid { frame(0, 1, 12, 3, 'c'); frame(0, 5, 12, 3, 'b'); frame(0, 9, 12, 3, 'p'); rect(2, 2, 3, 1, 'w'); rect(2, 6, 3, 1, 'c'); rect(2, 10, 3, 1, 'P') }
    private val number = grid { frame(1, 1, 10, 10, 'y'); rect(2, 2, 8, 1, 'w')
        rows(3, listOf("....kkkk....", "...kk..kk...", ".......kk...", "......kk....", ".....kk.....", "............", ".....kk.....")) }
    private val server = grid { frame(0, 0, 12, 4, 'g'); frame(0, 4, 12, 4, 'g'); frame(0, 8, 12, 4, 'g'); for (y in intArrayOf(1, 5, 9)) { rect(2, y, 5, 2, 'k'); rect(9, y, 1, 1, 'l'); rect(10, y + 1, 1, 1, 'r') } }

    private val bowl = literal(listOf(".kkkkkkkkkk.", "kbbbbbbbbbbk", ".kwwwwwwwwk.", ".kwwwbwwwwk.", "..kwwwwwwk..", "...kkkkkk..."))
    private val cup = grid(12, 9) { frame(1, 0, 8, 9, 'o'); rect(3, 2, 1, 3, 'y'); rect(9, 2, 2, 1, 'k'); rect(10, 3, 1, 3, 'k'); rect(9, 6, 2, 1, 'k') }
    private val wine = literal(listOf("..kkkkkkkk..", ".kNnnnnnnNk.", "kNnnnnnnnnNk", "kYYYYYYYYYYk", "kNnnrrrnnnNk", "kNnnrrrnnnNk", "kYYYYYYYYYYk", "kNnnnnnnnnNk", ".kNnnnnnnNk.", "..kkkkkkkk.."))
    private val cloth = literal(listOf(".kkkkkkkkkk.", "kPPPPPPPPPPk", "kPPppPPppPPk", "kPPPPPPPPPPk", "kppPPppPPppk", "kPPPPPPPPPPk", ".kkkkkkkkkk."))
    private val book = literal(listOf(".kkkkkkkkkk.", "kbbbbbbbbbbk", "kbbyyyyyybbk", "kbbbbbbbbbbk", "kbbyyyybbbbk", "kbbbbbbbbbbk", "kkkkkkkkkkkk", "kwswswswswsk", ".kkkkkkkkkk."))
    private val scroll = literal(listOf("kkkkkkkkkkkk", "kwwwwwwwwwwk", "kwwrwwwwbbwk", "kwrrrwwwwwwk", "kwwrwwwbbwwk", "kwwwwwwwwwwk", "kwwbbbbbbwwk", "kwwwwwwwwwwk", "kkkkkkkkkkkk"))
    private val wheat = literal(listOf(".....yy.....", "....yYyy....", "...yy.yyY...", "....yYyy....", "...yy.yyY...", "....yYyy....", ".....ey.....", ".....ee.....", ".....ee.....", ".....ee.....", ".....ee.....", "....eeee...."))
    private val corn = literal(listOf("....kkkk....", "...kyyYyk...", "...kyYyyk...", "...kYyyYk...", "...kyyYyk...", "...kyYyyk...", "..kekyYykek.", "..keeeyeeek.", "...keeeeek..", "....kkkkk..."))
    private val clock = literal(listOf("...kkkkkk...", "..kwwwwwwk..", ".kwwwwkwwwk.", "kwwwwwkwwwwk", "kwwwwwkwwwwk", "kwwwwwkkkwwk", "kwwwwwwwwwwk", "kwwwwwwwwwwk", ".kwwwwwwwwk.", "..kwwwwwwk..", "...kkkkkk..."))
    private val chip = literal(listOf(".g.g.g.g.g..", ".kkkkkkkkkk.", "gkBBBBBBBBkg", ".kBccccccBk.", "gkBcBBBBcBkg", ".kBcBkkBcBk.", "gkBcBkkBcBkg", ".kBcBBBBcBk.", "gkBccccccBkg", ".kBBBBBBBBk.", ".kkkkkkkkkk.", ".g.g.g.g.g.."))
    private val worker = grid { rect(3, 0, 6, 1, 'y'); rect(2, 1, 8, 2, 'y'); rect(2, 2, 8, 1, 'Y'); frame(3, 3, 6, 4, 't'); dot(4, 4, 'k'); dot(7, 4, 'k')
        rect(5, 6, 2, 1, 'N'); frame(1, 7, 10, 5, 'b'); rect(5, 8, 2, 3, 'y') }
    private val coinSprite = literal(listOf("...kkkkkk...", "..kyyyyyyk..", ".kywYYYYYyk.", "kyywyyyyYyyk", "kyyYyyyyYyyk", "kyyYyyyyYyyk", "kyyYyyyyYyyk", ".kyYYYYYYyk.", "..kyyyyyyk..", "...kkkkkk..."))
    private val plot = literal(listOf("kkkkkkkkkkkk", "knnlnnlnnlnk", "knNNNNNNNNnk", "knnnnnnnnnnk", "knNNNNNNNNnk", "knnnnnnnnnnk", "knNNNNNNNNnk", "knnnnnnnnnnk", "kkkkkkkkkkkk"))

    val coin: Sprite get() = coinSprite

    val byKey: Map<String, Sprite> = mapOf(
        "locker" to locker, "commit" to commit, "page" to page, "layer" to layer, "number" to number, "server" to server,
        "bowl" to bowl, "cup" to cup, "wine" to wine, "cloth" to cloth, "book" to book, "scroll" to scroll, "wheat" to wheat, "corn" to corn,
        "layers" to layer, "clock" to clock, "chip" to chip, "worker" to worker, "coin" to coinSprite, "plot" to plot,
    )
    fun of(key: String?, fallback: String = "coin"): Sprite = byKey[key] ?: byKey.getValue(fallback)
    val all: Collection<Sprite> get() = byKey.values + listOf(heart, star, sparkle)
    internal fun paletteColors(): Set<Char> = palette.keys
}

/** Grey, flat version of a sprite colour: eliminated and unearned things keep their shape but lose their colour. */
private fun Color.dimmed(): Color {
    val l = 0.3f * red + 0.59f * green + 0.11f * blue
    return Color(0.55f * l + 0.38f, 0.55f * l + 0.37f, 0.55f * l + 0.33f, alpha)
}

/** Draws [sprite] with its top-left at [topLeft], each sprite pixel [pixel] px wide. Pixel edges are snapped so no seams show. */
internal fun DrawScope.drawSprite(sprite: Sprite, topLeft: Offset, pixel: Float, dim: Boolean = false, alpha: Float = 1f) {
    for (y in 0 until sprite.height) for (x in 0 until sprite.width) {
        val c = palette[sprite.at(x, y)] ?: continue
        val x0 = floor(topLeft.x + x * pixel); val y0 = floor(topLeft.y + y * pixel)
        val x1 = ceil(topLeft.x + (x + 1) * pixel); val y1 = ceil(topLeft.y + (y + 1) * pixel)
        drawRect((if (dim) c.dimmed() else c).copy(alpha = alpha * c.alpha), Offset(x0, y0), Size(x1 - x0, y1 - y0))
    }
}

/** A sprite sized to fit a square of [size], scaled by whole-ish pixels. */
@Composable
internal fun PixelSprite(sprite: Sprite, size: Dp, modifier: Modifier = Modifier, dim: Boolean = false, alpha: Float = 1f) {
    val s = remember(sprite) { sprite }
    Canvas(modifier.size(size)) {
        val pixel = minOf(this.size.width / s.width, this.size.height / s.height)
        val offset = Offset((this.size.width - pixel * s.width) / 2f, (this.size.height - pixel * s.height) / 2f)
        drawSprite(s, offset, pixel, dim, alpha)
    }
}

@Composable
internal fun PixelSprite(key: String?, size: Dp, modifier: Modifier = Modifier, dim: Boolean = false, alpha: Float = 1f) =
    PixelSprite(Sprites.of(key), size, modifier, dim, alpha)

