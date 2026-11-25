package com.anything2game.app.ui.space

import com.anything2game.contract.FrameId
import com.anything2game.contract.game.EvidenceRef
import com.anything2game.contract.space.ActivityRef
import com.anything2game.contract.space.LearningPath
import com.anything2game.contract.space.PathNode
import com.anything2game.engine.progress.NodeStatus
import com.anything2game.engine.progress.Standing

/** What a step asks the learner to do. Decides the node's icon and the name on its card; a practice step is named and drawn by its frame. */
internal sealed interface StepKind {
    data object Explain : StepKind
    data object Game : StepKind
    data class Practice(val frame: FrameId) : StepKind
}

/** What the learner sees at a glance. CURRENT is the one step the map invites you to start. */
internal enum class StepState { DONE, REVIEW, CURRENT, AVAILABLE, LOCKED, UPDATE }

internal data class RouteStep(
    val node: PathNode, val number: Int, val kind: StepKind, val state: StepState, val title: String,
    /** How the learner is doing with this step, in the words every screen uses. [state] adds where it sits on the route. */
    val standing: Standing,
    /** Page and material of the first cited source, when the step has one. */
    val source: StepSource?,
    /** For a locked step, the title of the first unfinished step it waits for. */
    val waitingFor: String?,
    /** Completed at least once. A completed step that is due for review is still finished, even while it is the one to do next. */
    val finished: Boolean,
) { val id get() = node.nodeId }

internal data class StepSource(val evidence: EvidenceRef.Pdf, val material: String?, val page: String)

internal data class RouteSection(val number: Int, val title: String, val material: String?, val steps: List<RouteStep>, val fixture: Boolean) {
    val done get() = steps.count { it.finished }
}

/**
 * The one reading of a route's progress. The map, the progress strip and the card on the Spaces tab all take
 * "what is next" ([current]), "how many are finished" ([done]) and "how many steps" ([total]) from here.
 */
internal data class RouteMap(val sections: List<RouteSection>) {
    val steps get() = sections.flatMap { it.steps }
    val current get() = steps.firstOrNull { it.state == StepState.CURRENT }
    val done get() = sections.sumOf { it.done }
    val total get() = steps.size
}

/** Test generators once put "(mock)" in every concept title. Old saved routes keep it; the section badge says it once instead. */
internal fun cleanTitle(title: String) = title.replace(Regex("\\s*[（(]\\s*mock\\s*[）)]\\s*$", RegexOption.IGNORE_CASE), "").trim().ifBlank { title }

/**
 * Turns a route and its progress into what the map draws: nodes grouped into sections (the node's chapter, otherwise the material
 * it comes from), each with a state. A step whose prerequisites are unfinished is locked; the first open step is the current one.
 */
internal fun routeMap(
    path: LearningPath, status: Map<String, NodeStatus>, standings: Map<String, Standing>,
    materialOf: (PathNode) -> MaterialInfo?, sourceTitle: (PathNode, EvidenceRef.Pdf) -> String?,
): RouteMap {
    val titles = path.nodes.associate { it.nodeId to cleanTitle(it.title) }
    val finished = path.nodes.filter { status[it.nodeId] == NodeStatus.COMPLETE || status[it.nodeId] == NodeStatus.REVIEW }.map { it.nodeId }.toSet()
    val currentId = path.nodes.firstOrNull { status[it.nodeId] == NodeStatus.AVAILABLE }?.nodeId
        ?: path.nodes.firstOrNull { status[it.nodeId] == NodeStatus.REVIEW }?.nodeId
    val steps = path.nodes.mapIndexed { index, node ->
        val state = when (status[node.nodeId] ?: NodeStatus.NEEDS_UPDATE) {
            NodeStatus.COMPLETE -> StepState.DONE
            NodeStatus.REVIEW -> if (node.nodeId == currentId) StepState.CURRENT else StepState.REVIEW
            NodeStatus.AVAILABLE -> if (node.nodeId == currentId) StepState.CURRENT else StepState.AVAILABLE
            NodeStatus.LOCKED -> StepState.LOCKED
            NodeStatus.NEEDS_UPDATE -> StepState.UPDATE
        }
        val cited = node.evidence.filterIsInstance<EvidenceRef.Pdf>().firstOrNull()
        RouteStep(node, index + 1, kindOf(node.activity), state, titles.getValue(node.nodeId), standings[node.nodeId] ?: Standing.NEW,
            cited?.let { StepSource(it, sourceTitle(node, it), it.displayPage()) },
            if (state == StepState.LOCKED) node.prerequisites.firstOrNull { it !in finished }?.let(titles::get) else null,
            node.nodeId in finished)
    }
    // The material is only worth naming in the banner when the route mixes several.
    val mixed = path.nodes.mapNotNull { materialOf(it)?.title }.toSet().size > 1
    val sections = mutableListOf<RouteSection>()
    var open: Triple<String, String?, MutableList<RouteStep>>? = null
    var fixture = false
    fun close() { open?.let { (title, material, list) -> sections += RouteSection(sections.size + 1, title, material, list, fixture) }; open = null; fixture = false }
    for (step in steps) {
        val material = materialOf(step.node)
        val title = step.node.chapter ?: material?.title ?: path.title
        if (open?.first != title) { close(); open = Triple(title, material?.title?.takeIf { mixed && it != title }, mutableListOf()) }
        open!!.third += step
        if (material?.fixture == true) fixture = true
    }
    close()
    return RouteMap(sections)
}

internal data class MaterialInfo(val title: String, val fixture: Boolean)

internal fun kindOf(activity: ActivityRef): StepKind = when (activity) {
    is ActivityRef.Tutor -> StepKind.Explain
    is ActivityRef.Game -> StepKind.Game
    is ActivityRef.Practice -> StepKind.Practice(activity.frame)
}
