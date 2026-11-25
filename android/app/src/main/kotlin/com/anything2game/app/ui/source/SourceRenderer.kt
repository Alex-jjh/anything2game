package com.anything2game.app.ui.source

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One way of showing a source's pages inside the viewer. The viewer owns the frame (title, page number, prev/next, the
 * pager) and asks the renderer only for what differs: how many pages, where to open, and what one page looks like.
 * [OriginalPdfRenderer] draws the real file; [ExtractedTextRenderer] prints the text taken from it. A deck of slide images
 * would be a third renderer and a new case of `Original`, with no screen changing.
 */
internal interface SourceRenderer {
    val pageCount: Int
    /** 0-based index of the page to open on. */
    val startPage: Int
    /** Whether the marker legend ("Quoted in the explanation") belongs under the pages: something is marked. */
    val legend: Boolean
    /** False while a page is zoomed in, so a drag pans the page instead of turning it. */
    val swipeable: Boolean get() = true
    @Composable fun Page(index: Int, current: Boolean, modifier: Modifier)
}
