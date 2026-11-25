package com.anything2game.app.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.anything2game.app.R
import com.anything2game.contract.game.AllocationConfig
import com.anything2game.contract.game.AllocationTheme
import com.anything2game.contract.game.PartitionConfig
import com.anything2game.contract.game.PartitionTheme
import com.anything2game.contract.game.icon
import com.anything2game.contract.game.icons
import java.util.Locale

/** The words and pictures of a partition board. Each missing theme field falls back to the neutral locker wording of the first release. */
internal class PartitionLook(val item: String, val items: String, val target: String, val query: String, private val ask: String, val icon: String,
    private val where: String? = null) {
    fun askAbout(n: Int) = ask.replace("{n}", n.toString())
    /** "The word is somewhere in pages 1–12", or null when the theme has no sentence for it (the client then uses a neutral one). */
    fun whereIs(low: Int, high: Int) = where?.replace("{from}", low.toString())?.replace("{to}", high.toString())
}

internal class AllocationLook(val firstZone: String, val secondZone: String, val firstGood: String, val firstGoods: String,
    val secondGood: String, val secondGoods: String, val token: String, val tokens: String,
    val firstIcon: String, val secondIcon: String, val tokenIcon: String) {
    fun zone(first: Boolean) = if (first) firstZone else secondZone
    fun good(first: Boolean, plural: Boolean) = if (first) (if (plural) firstGoods else firstGood) else (if (plural) secondGoods else secondGood)
    fun icon(first: Boolean) = if (first) firstIcon else secondIcon
}

@Composable
internal fun PartitionConfig.look(): PartitionLook {
    val t = theme ?: PartitionTheme()
    return PartitionLook(t.item ?: stringResource(R.string.look_locker), t.items ?: t.item?.let { it } ?: stringResource(R.string.look_lockers),
        t.target ?: stringResource(R.string.look_locker_target), t.query ?: stringResource(R.string.look_questions),
        t.ask ?: stringResource(R.string.games_ask), icon(), t.where)
}

@Composable
internal fun AllocationConfig.look(): AllocationLook {
    val t = theme ?: AllocationTheme()
    val goodA = stringResource(R.string.look_good_a); val goodsA = stringResource(R.string.look_goods_a)
    val goodB = stringResource(R.string.look_good_b); val goodsB = stringResource(R.string.look_goods_b)
    return AllocationLook(t.firstZone ?: stringResource(R.string.look_zone, "A"), t.secondZone ?: stringResource(R.string.look_zone, "B"),
        t.firstGood ?: goodA, t.firstGoods ?: t.firstGood ?: goodsA, t.secondGood ?: goodB, t.secondGoods ?: t.secondGood ?: goodsB,
        t.token ?: stringResource(R.string.look_token), t.tokens ?: t.token ?: stringResource(R.string.look_tokens),
        icons().first, icons().second, icons().token)
}

/** 1.5, 0.67, 4: at most two decimals, no trailing zeros. Always uses '.', which both languages read the same way. */
internal fun ratioText(value: Double): String {
    val text = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    return text.ifEmpty { "0" }
}
