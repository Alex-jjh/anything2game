package com.anything2game.contract.game

import com.anything2game.contract.LearningPackage
import com.anything2game.contract.PackageValidator
import com.anything2game.contract.SourceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

const val GAME_SCHEMA = "a2g.game/v1"

@Serializable
data class ContentVersion(val contentId: String, val contentVersion: String) {
    fun validate() {
        requireContentId(contentId)
        requireContentId(contentVersion)
    }
}

fun requireContentId(id: String) {
    require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))) { "Invalid content identifier" }
}

@Serializable
sealed interface EvidenceRef {
    val evidenceId: String

    @Serializable @SerialName("pdf")
    data class Pdf(override val evidenceId: String, val documentId: String, val documentVersion: String,
        val chunkId: String, val quote: String, val pageIndex: Int, val pageLabel: String? = null) : EvidenceRef {
        /** The page as a reader names it: the printed number when known, else the physical page counted from 1. */
        fun displayPage(): String = pageLabel ?: (pageIndex + 1).toString()
    }

    @Serializable @SerialName("reviewed-card")
    data class Reviewed(override val evidenceId: String, val cardId: String, val cardVersion: String,
        val sourceTitle: String, val sourceUrl: String, val section: String, val fact: String,
        val reviewNote: String) : EvidenceRef

    @Serializable @SerialName("external")
    data class External(override val evidenceId: String, val title: String, val url: String, val digestSha256: String,
        val sectionOrExcerpt: String) : EvidenceRef

    fun validate() {
        requireContentId(evidenceId)
        when (this) {
            is Pdf -> {
                // v1 document versions may be tagged digests and chunk IDs are opaque lookup keys.
                require(documentId.isNotBlank() && documentVersion.isNotBlank())
                require(pageIndex >= 0 && quote.isNotBlank() && quote.length <= 3000)
            }
            is Reviewed -> {
                ContentVersion(cardId, cardVersion).validate()
                require(listOf(sourceTitle, section, fact, reviewNote).all { it.isNotBlank() })
                requireWebUrl(sourceUrl)
            }
            is External -> {
                require(title.isNotBlank() && sectionOrExcerpt.isNotBlank() && digestSha256.matches(Regex("[a-f0-9]{64}")))
                requireWebUrl(url)
            }
        }
    }
}

private fun requireWebUrl(url: String) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "Expected HTTPS source URL" }
}

/** Resolve the reference against the specific immutable package before it leaves its v1 scope. */
fun LearningPackage.scopeEvidence(ref: SourceRef, evidenceId: String): EvidenceRef.Pdf {
    val chunk = requireNotNull(chunk(ref.chunkId))
    require(ref.quote.isNotBlank() && PackageValidator.normalise(ref.quote) in PackageValidator.normalise(chunk.text))
    return EvidenceRef.Pdf(evidenceId, documentId, documentVersion, chunk.chunkId, ref.quote, chunk.pageIndex, chunk.pageLabel)
}

@Serializable
sealed interface StageConfig {
    val mechanicId: String
    /** The configuration with its presentation removed: two stages with equal rules are the same conditions. */
    fun rulesOnly(): StageConfig
}

/**
 * What the board is *about*. Purely presentational: it never changes a rule, a reducer outcome or a checkpoint, so
 * [StageConfig.rulesOnly] drops it before conditions are compared. Every field is optional; a missing field falls back to the
 * client's neutral wording, which keeps older packs and saved games readable.
 *
 * Nouns are lower-case words for use inside a sentence ("commit", "commits"); [query] is the plural noun for the budget ("test runs"),
 * and Chinese nouns carry their measure word ("个提交"). [icon] is a key of [GameIcons].
 */
@Serializable
data class PartitionTheme(val item: String? = null, val items: String? = null, val target: String? = null,
    val query: String? = null, val ask: String? = null, val icon: String? = null, val where: String? = null) {
    fun validate() {
        listOfNotNull(item, items, target, query, ask, where).forEach(::requireThemeText)
        require(ask == null || "{n}" in ask) { "The question label must contain {n}" }
        require(where == null || ("{from}" in where && "{to}" in where)) { "The range label must contain {from} and {to}" }
        require(icon == null || icon in GameIcons.partition) { "Unknown partition icon" }
    }
}

/** Zone, good and token names for the allocation board. Goods use singular + plural so the rules can be spoken in a sentence. */
@Serializable
data class AllocationTheme(val firstZone: String? = null, val secondZone: String? = null,
    val firstGood: String? = null, val firstGoods: String? = null, val secondGood: String? = null, val secondGoods: String? = null,
    val token: String? = null, val tokens: String? = null,
    val firstIcon: String? = null, val secondIcon: String? = null, val tokenIcon: String? = null) {
    fun validate() {
        listOfNotNull(firstZone, secondZone, firstGood, firstGoods, secondGood, secondGoods, token, tokens).forEach(::requireThemeText)
        listOfNotNull(firstIcon, secondIcon, tokenIcon).forEach { require(it in GameIcons.allocation) { "Unknown allocation icon" } }
    }
}

private fun requireThemeText(text: String) =
    require(text.isNotBlank() && text.length <= 60 && text.none { it.isISOControl() }) { "Invalid theme text" }

/** The fixed set of drawable icon keys. A key outside the set is rejected rather than silently shown as a blank. */
object GameIcons {
    val partition = setOf("locker", "commit", "page", "layer", "number", "server")
    val allocation = setOf("bowl", "cup", "wine", "cloth", "book", "scroll", "wheat", "corn", "layers", "clock", "chip", "worker", "coin", "plot")
    const val DEFAULT_PARTITION = "locker"
    const val DEFAULT_FIRST = "bowl"
    const val DEFAULT_SECOND = "cup"
    const val DEFAULT_TOKEN = "coin"
}

/** The three pictures of an allocation board, with the neutral defaults filled in where its theme names none. */
data class AllocationIcons(val first: String, val second: String, val token: String)

/** The picture of a partition board's tiles; the one place its neutral default is written. */
fun PartitionConfig.icon(): String = theme?.icon ?: GameIcons.DEFAULT_PARTITION
fun AllocationConfig.icons(): AllocationIcons = AllocationIcons(theme?.firstIcon ?: GameIcons.DEFAULT_FIRST,
    theme?.secondIcon ?: GameIcons.DEFAULT_SECOND, theme?.tokenIcon ?: GameIcons.DEFAULT_TOKEN)

/** The picture a game is known by: its tiles, or the first of its two places. */
fun StageConfig.mainIcon(): String = when (this) {
    is PartitionConfig -> icon()
    is AllocationConfig -> icons().first
}

@Serializable @SerialName("partition")
data class PartitionConfig(val candidates: Int, val queryBudget: Int, val theme: PartitionTheme? = null) : StageConfig {
    override val mechanicId get() = MechanicRegistry.PARTITION
    override fun rulesOnly() = copy(theme = null)
}

@Serializable @SerialName("allocation")
data class AllocationConfig(val tokens: Int, val firstYield: Int, val secondYield: Int, val firstGoal: Int,
    val secondGoal: Int, val theme: AllocationTheme? = null) : StageConfig {
    override val mechanicId get() = MechanicRegistry.ALLOCATION
    override fun rulesOnly() = copy(theme = null)
}

@Serializable
data class GameStage(val stageId: String, val goal: String, val config: StageConfig, val transfer: Boolean,
    val evidenceIds: List<String>, val rulesVersion: Int = MechanicRegistry.VERSION) {
    val mechanicId get() = config.mechanicId
}

/** What a scene of the "Quick look" is for. The order of a reviewed game's scenes follows this order. */
@Serializable
enum class IntroKind { HOOK, STORY, IDEA, HOW_IT_WORKS, HOW_TO_PLAY }

/**
 * One screen of the short story told before a game: a hook question, a tiny everyday story, the idea in one plain sentence, a
 * picture of how it works and how to play. [visual] names an animated picture the client knows ([IntroVisuals]); null lets the client
 * choose by [kind]. A HOW_TO_PLAY body is up to four short lines (goal, play, win, stars) separated by a line break.
 */
@Serializable
data class IntroScene(val kind: IntroKind, val title: String, val body: String, val visual: String? = null) {
    fun validate() {
        require(title.isNotBlank() && title.length <= 80 && body.isNotBlank() && body.length <= 500) { "Invalid intro scene" }
        require(visual == null || visual in IntroVisuals.all) { "Unknown intro visual" }
    }
}

object IntroVisuals {
    /** A row of tiles that halves; two zones with a token moving between them. */
    const val HALVING = "halving"
    const val TRADE = "trade"
    val all = setOf(HALVING, TRADE)
}

/**
 * [intro] is the optional "Quick look" told before the first round (empty = none, which is what every older game decodes to);
 * [learn] is the one line "what you will learn" shown on the game's tile. Neither one changes a rule.
 */
@Serializable
data class GameSpec(val gameId: String, val gameVersion: String, val title: String, val language: String,
    val conceptRefs: List<String>, val evidenceRefs: List<EvidenceRef>, val teachingSetting: String,
    val stages: List<GameStage>, val requiredCapabilities: Set<String>, val schema: String = GAME_SCHEMA,
    val intro: List<IntroScene> = emptyList(), val learn: String? = null)

/** These descriptors are also the source for generation prompts and capability negotiation. */
object MechanicRegistry {
    const val PARTITION = "information-partition"
    const val ALLOCATION = "resource-allocation"
    const val VERSION = 1
    const val MAX_ACTIONS = 512
    val candidates = 2..32
    val queries = 1..8
    val tokens = 2..12
    val yields = 1..12
    val goals = 1..(tokens.last * yields.last)
    val stageCount = 2..8
    fun capability(id: String) = "$id/v$VERSION"
    val capabilities = setOf(capability(PARTITION), capability(ALLOCATION))

    fun describe(): String = """
        partition: $PARTITION v$VERSION, candidates=$candidates, queryBudget=$queries; split after an ordered candidate.
        allocation: $ALLOCATION v$VERSION, tokens=$tokens, firstYield/secondYield=$yields, firstGoal/secondGoal=$goals; place, undo, submit.
        Optional presentation: partition theme (item, items, target, query, ask containing {n}, icon in ${GameIcons.partition}); allocation theme (firstZone, secondZone, firstGood(s), secondGood(s), token(s), firstIcon/secondIcon/tokenIcon in ${GameIcons.allocation}). Themes never change the rules.
        Stages=$stageCount. End with a changed-condition transfer. Required capabilities: ${capabilities.joinToString()}.
    """.trimIndent()

    fun validate(spec: GameSpec) {
        ContentVersion(spec.gameId, spec.gameVersion).validate()
        require(spec.schema == GAME_SCHEMA && spec.title.isNotBlank() && spec.language.isNotBlank())
        require(spec.teachingSetting.isNotBlank() && spec.conceptRefs.isNotEmpty() && spec.conceptRefs.none(String::isBlank))
        require(spec.stages.size in stageCount && spec.stages.distinctBy { it.stageId }.size == spec.stages.size)
        require(spec.evidenceRefs.isNotEmpty() && spec.evidenceRefs.distinctBy { it.evidenceId }.size == spec.evidenceRefs.size)
        spec.evidenceRefs.forEach { it.validate() }
        require(spec.intro.size <= 8 && spec.learn.orEmpty().length <= 200) { "Intro too long" }
        spec.intro.forEach { it.validate() }
        val evidenceIds = spec.evidenceRefs.map { it.evidenceId }.toSet()
        require(spec.requiredCapabilities == spec.stages.map { capability(it.mechanicId) }.toSet()) { "Unsupported or missing capability" }
        require(!spec.stages.first().transfer && spec.stages.last().transfer) { "Missing practice/transfer stage" }
        spec.stages.forEachIndexed { i, stage ->
            requireContentId(stage.stageId)
            require(stage.rulesVersion == VERSION) { "Unsupported rules version" }
            require(stage.goal.isNotBlank() && stage.evidenceIds.isNotEmpty() && evidenceIds.containsAll(stage.evidenceIds))
            if (stage.transfer) {
                val practice = spec.stages.take(i).filter { !it.transfer && it.mechanicId == stage.mechanicId }
                require(practice.isNotEmpty() && practice.none { it.config.rulesOnly() == stage.config.rulesOnly() }) { "Transfer must change conditions" }
            }
            when (val c = stage.config) {
                is PartitionConfig -> {
                    require(c.candidates in candidates && c.queryBudget in queries)
                    c.theme?.validate()
                }
                is AllocationConfig -> {
                    require(c.tokens in tokens && c.firstYield in yields && c.secondYield in yields &&
                        c.firstGoal in goals && c.secondGoal in goals)
                    c.theme?.validate()
                }
            }
        }
    }
}

@Serializable enum class Zone { FIRST, SECOND }

@Serializable
sealed interface GameAction {
    @Serializable @SerialName("split") data class Split(val after: Int) : GameAction
    @Serializable @SerialName("place") data class Place(val zone: Zone) : GameAction
    @Serializable @SerialName("undo") data object Undo : GameAction
    @Serializable @SerialName("reset") data object Reset : GameAction
    @Serializable @SerialName("submit") data object Submit : GameAction
    @Serializable @SerialName("advance") data object Advance : GameAction
    @Serializable @SerialName("help") data object Help : GameAction
}

@Serializable
data class GameCheckpoint(val gameId: String, val gameVersion: String, val rulesVersions: List<Int>,
    val seed: Long, val actions: List<GameAction>)
