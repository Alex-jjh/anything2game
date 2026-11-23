package com.anything2game.app

import com.anything2game.app.ui.game.Sprites
import com.anything2game.contract.game.GameIcons
import org.junit.Assert.*
import org.junit.Test

class PixelArtTest {
    @Test fun every_icon_key_the_contract_allows_has_a_sprite_and_every_sprite_pixel_has_a_colour() {
        for (key in GameIcons.partition + GameIcons.allocation) assertNotNull("sprite for $key", Sprites.byKey[key])
        val colours = Sprites.paletteColors()
        for (sprite in Sprites.all) for (y in 0 until sprite.height) for (x in 0 until sprite.width) {
            val c = sprite.at(x, y)
            assertTrue("pixel '$c' has no colour", c == '.' || c in colours)
        }
    }

    @Test fun sprites_are_small_and_not_empty() {
        for (sprite in Sprites.all) {
            assertTrue(sprite.width in 5..14 && sprite.height in 5..14)
            assertTrue((0 until sprite.height).any { y -> (0 until sprite.width).any { x -> sprite.at(x, y) != '.' } })
        }
    }
}
