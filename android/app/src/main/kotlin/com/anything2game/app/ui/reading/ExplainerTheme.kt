package com.anything2game.app.ui.reading

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.anything2game.app.R
import com.anything2game.app.ui.character.BlobAvatar
import com.anything2game.app.ui.character.BlobLook
import com.anything2game.app.ui.character.BlobShape
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.character.Topper
import com.anything2game.app.ui.theme.A2g

/** What the explainer is doing right now. Themes map each one to a picture (a mood on the procedural character, a drawable for art). */
enum class ExplainerExpression { EXPLAINING, EXAMPLE, REPHRASE, ANSWER, DONE, THINKING }

/**
 * The look of the explainer scene: what is behind the character and how the character is drawn.
 * The swap point for the team's art; the layout, paging and controls never change with the theme.
 */
@Stable
interface ExplainerTheme {
    val id: String
    @get:StringRes val nameRes: Int
    /** The explainer's name in the dialogue box. */
    @get:StringRes val personaNameRes: Int
    /** A theme that needs bundled art is simply not offered while the art is missing. */
    fun isAvailable(context: Context): Boolean

    /** Everything behind the character: fills its parent. */
    @Composable fun Backdrop(modifier: Modifier)

    /** The explainer, large, standing on the bottom edge of [modifier]'s box. */
    @Composable fun Standee(expression: ExplainerExpression, modifier: Modifier)
}

/** The explainer persona of the default theme: a blob like the player's, but never one of the three the player can pick. */
internal val AyaAvatar = BlobAvatar(
    "aya", R.string.explainer_name, R.string.explainer_trait,
    BlobLook(body = Color(0xFFF7DE8F), outline = Color(0xFF8A6A1F), cheek = Color(0xFFF08A7A), accent = Color(0xFFB04A5A), shape = BlobShape.ROUND, topper = Topper.ANTENNA),
    emptyMap(),
)

/** Default: paper-and-ink colours of the current skin, a soft gradient and a procedural character. Needs no art. */
object PaperExplainerTheme : ExplainerTheme {
    override val id = "paper"
    override val nameRes = R.string.explainer_theme_paper
    override val personaNameRes = R.string.explainer_name
    override fun isAvailable(context: Context) = true

    @Composable override fun Backdrop(modifier: Modifier) {
        val skin = A2g.skin
        Box(modifier.background(Brush.verticalGradient(listOf(skin.evidenceSoft, skin.paper)))) {
            // A soft floor under the character so it does not float.
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * 0.62f
                val h = size.height * 0.07f
                drawOval(skin.outline.copy(alpha = 0.16f), Offset((size.width - w) / 2f, size.height - h * 1.5f), Size(w, h))
            }
        }
    }

    @Composable override fun Standee(expression: ExplainerExpression, modifier: Modifier) {
        AyaAvatar.Draw(
            when (expression) {
                ExplainerExpression.EXPLAINING, ExplainerExpression.REPHRASE -> Mood.IDLE
                ExplainerExpression.EXAMPLE, ExplainerExpression.ANSWER -> Mood.HAPPY
                ExplainerExpression.DONE -> Mood.CELEBRATE
                ExplainerExpression.THINKING -> Mood.THINKING
            },
            modifier,
        )
    }
}

/**
 * Drop-in for illustrated art. Offered only when the app bundles a drawable named `standee_idle`.
 * Optional drawables, all looked up by name at runtime (PNG, WebP or vector):
 * `standee_explaining`, `standee_example`, `standee_rephrase`, `standee_answer`, `standee_done`, `standee_thinking`
 * (a missing one falls back to `standee_idle`) and `explainer_bg` (a background picture; a scrim keeps the text readable).
 */
class IllustratedExplainerTheme(
    private val lookup: (Context, String) -> Int = ::drawableIdentifier,
) : ExplainerTheme {
    override val id = "illustrated"
    override val nameRes = R.string.explainer_theme_illustrated
    override val personaNameRes = R.string.explainer_name
    override fun isAvailable(context: Context) = lookup(context, STANDEE_IDLE) != 0

    @Composable override fun Backdrop(modifier: Modifier) {
        val context = LocalContext.current
        val skin = A2g.skin
        @DrawableRes val bg = remember(context) { lookup(context, BACKGROUND) }
        Box(modifier) {
            if (bg != 0) Image(painterResource(bg), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(skin.evidenceSoft, skin.paper))))
            // Scrim: paper (or night) colour over the picture, stronger at the bottom where the dialogue sits.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(skin.paper.copy(alpha = 0.25f), skin.paper.copy(alpha = 0.75f)))))
        }
    }

    @Composable override fun Standee(expression: ExplainerExpression, modifier: Modifier) {
        val context = LocalContext.current
        @DrawableRes val res = remember(context, expression) {
            lookup(context, "standee_" + expression.name.lowercase()).takeIf { it != 0 } ?: lookup(context, STANDEE_IDLE)
        }
        if (res != 0) Image(painterResource(res), null, modifier, contentScale = ContentScale.Fit, alignment = Alignment.BottomCenter)
    }

    companion object {
        const val STANDEE_IDLE = "standee_idle"
        const val BACKGROUND = "explainer_bg"
    }
}

@SuppressLint("DiscouragedApi")
private fun drawableIdentifier(context: Context, name: String): Int = context.resources.getIdentifier(name, "drawable", context.packageName)

/** The themes the app knows, in the order they are offered. */
object ExplainerThemes {
    val all: List<ExplainerTheme> = listOf(PaperExplainerTheme, IllustratedExplainerTheme())

    /** Themes that can be shown right now (art bundled). The default is always among them. */
    fun available(context: Context, themes: List<ExplainerTheme> = all): List<ExplainerTheme> = themes.filter { it.isAvailable(context) }

    /** The saved choice, or the default when nothing was saved or the saved theme lost its art. */
    fun resolve(context: Context, savedId: String?, themes: List<ExplainerTheme> = all): ExplainerTheme =
        available(context, themes).firstOrNull { it.id == savedId } ?: PaperExplainerTheme
}
