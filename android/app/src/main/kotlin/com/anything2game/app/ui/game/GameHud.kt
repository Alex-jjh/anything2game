package com.anything2game.app.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anything2game.app.ui.theme.A2g

/** The chamfered corner every game surface shares: a rectangle with its corners cut, the 8-bit counterpart of a rounded card. */
internal fun pixelShape(cut: Dp = 6.dp) = CutCornerShape(cut)

/** A panel with a thick ink outline and a hard offset shadow: no blur, so it reads as drawn rather than lifted. */
@Composable
internal fun PixelPanel(modifier: Modifier = Modifier, fill: Color = A2g.skin.card, outline: Color = A2g.skin.ink,
    padding: PaddingValues = PaddingValues(14.dp), onClick: (() -> Unit)? = null, fillHeight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(Modifier.matchParentSize().offset(4.dp, 4.dp).background(outline.copy(alpha = 0.85f), pixelShape()))
        // [fillHeight]: the panel takes the height its parent gives it (tiles of one row share the tallest tile's height).
        Column(Modifier.then(if (fillHeight) Modifier.fillMaxHeight() else Modifier).background(fill, pixelShape()).border(2.dp, outline, pixelShape())
            .let { if (onClick != null) it.clip(pixelShape()).clickable(role = Role.Button, onClick = onClick) else it }.padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** A chunky button that presses into its own shadow. [primary] is the one action of a moment (ask, deliver, next). */
@Composable
internal fun PixelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false,
    leading: (@Composable () -> Unit)? = null) {
    val skin = A2g.skin
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = when { !enabled -> skin.paper; primary -> skin.action; else -> skin.card }
    val ink = when { !enabled -> skin.inkMuted; primary -> skin.onAction; else -> skin.ink }
    val edge = if (enabled) skin.ink else skin.outline
    val lift = if (pressed && enabled) 3.dp else 0.dp
    val shadow = edge.copy(alpha = if (enabled) 0.9f else 0.5f)
    Row(modifier.heightIn(min = 48.dp).padding(bottom = 3.dp)
        .drawBehind {
            val outline = pixelShape(5.dp).createOutline(size, layoutDirection, this)
            translate(0f, 3.dp.toPx()) { drawOutline(outline, shadow) }
        }
        .offset(0.dp, lift).background(fill, pixelShape(5.dp)).border(BorderStroke(2.dp, edge), pixelShape(5.dp))
        .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Text(text, color = ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

/** A small label in a cut-corner pill: mechanism, stage, "New", "Demo". */
@Composable
internal fun PixelTag(text: String, modifier: Modifier = Modifier, fill: Color = A2g.skin.evidenceSoft, ink: Color = A2g.skin.ink, large: Boolean = false) {
    Text(text, modifier.background(fill, pixelShape(4.dp)).border(1.5.dp, ink.copy(alpha = 0.75f), pixelShape(4.dp))
        .padding(horizontal = if (large) 9.dp else 7.dp, vertical = if (large) 4.dp else 2.dp), color = ink, fontSize = if (large) 14.sp else 11.sp,
        lineHeight = if (large) 18.sp else 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
}

/** [total] hearts, the first [left] full. A spent heart stays as a grey outline so the budget's size is always visible. */
@Composable
internal fun HeartsRow(total: Int, left: Int, size: Dp = 22.dp, description: String, modifier: Modifier = Modifier) {
    Row(modifier.semantics { contentDescription = description }.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total) { i -> PixelSprite(Sprites.heart, size, dim = i >= left) }
    }
}

/** One to three stars. With [animate] they pop in one after the other; the unearned ones are grey. */
@Composable
internal fun StarsRow(stars: Int, size: Dp = 32.dp, animate: Boolean = false, modifier: Modifier = Modifier, total: Int = 3) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        repeat(total) { i ->
            val earned = i < stars
            val scale = remember { Animatable(if (animate && earned) 0f else 1f) }
            LaunchedEffect(animate, earned) {
                if (animate && earned) {
                    kotlinx.coroutines.delay(180L * i + 150)
                    scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                }
            }
            PixelSprite(Sprites.star, size, Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, dim = !earned)
        }
    }
}
