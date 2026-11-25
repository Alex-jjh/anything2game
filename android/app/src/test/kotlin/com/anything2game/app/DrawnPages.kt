package com.anything2game.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.anything2game.app.ui.reading.PageImages

/** Robolectric cannot render a PDF: these pages are drawn by hand (a title bar and lines of "text") so a viewer shows something page-like. */
class DrawnPages(override val pageCount: Int) : PageImages {
    val rendered = mutableListOf<Int>()
    override suspend fun render(index: Int, widthPx: Int): ImageBitmap {
        rendered += index
        val w = widthPx.coerceAtLeast(60)
        val h = (w * 11f / 8.5f).toInt()
        val image = ImageBitmap(w, h)
        val canvas = Canvas(image)
        fun fill(r: Rect, c: Color) = canvas.drawRect(r, Paint().apply { color = c })
        fill(Rect(0f, 0f, w.toFloat(), h.toFloat()), Color.White)
        fill(Rect(w * 0.18f, h * 0.07f, w * 0.82f, h * 0.085f), Color(0xFF222222))
        fill(Rect(w * 0.30f, h * 0.10f, w * 0.70f, h * 0.112f), Color(0xFF777777))
        var y = h * 0.16f
        var line = index
        while (y < h * 0.92f) {
            val right = if (line % 7 == 6) 0.55f else 0.84f
            fill(Rect(w * 0.12f, y, w * right, y + h * 0.008f), Color(0xFF555555))
            y += h * 0.021f
            line++
        }
        return image
    }
    override fun close() {}
}
