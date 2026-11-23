package com.anything2game.contract.space

import com.anything2game.contract.FrameId
import com.anything2game.contract.game.*
import com.anything2game.contract.progress.ActivityKey
import com.anything2game.contract.progress.ActivityKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface SpaceMember {
    @Serializable @SerialName("server-document")
    data class ServerDocumentRef(val documentId: String, val documentVersion: String) : SpaceMember
    @Serializable @SerialName("local-pack")
    data class LocalPackRef(val packageId: String, val contentVersion: String) : SpaceMember

    fun version(): ContentVersion = when (this) {
        is ServerDocumentRef -> ContentVersion(documentId, documentVersion)
        is LocalPackRef -> ContentVersion(packageId, contentVersion)
    }
}

@Serializable
data class Space(val spaceId: String, val title: String, val revision: Long,
    val members: List<SpaceMember> = emptyList(), val activePathVersion: ContentVersion? = null)

@Serializable data class CreateSpaceRequest(val title: String, val clientRequestId: String)
@Serializable data class RenameSpaceRequest(val title: String, val expectedRevision: Long, val clientRequestId: String)
@Serializable data class SpaceMemberRequest(val member: SpaceMember, val expectedRevision: Long, val clientRequestId: String)

private const val TUTOR_RULES_VERSION = 1
private const val PRACTICE_RULES_VERSION = 1
const val PATH_SCHEMA = "a2g.path/v1"

@Serializable
sealed interface ActivityRef {
    val contentId: String
    val contentVersion: String
    val activityId: String
    val rulesVersion: Int

    @Serializable @SerialName("tutor")
    data class Tutor(override val contentId: String, override val contentVersion: String, override val activityId: String,
        override val rulesVersion: Int = TUTOR_RULES_VERSION) : ActivityRef
    @Serializable @SerialName("practice")
    data class Practice(override val contentId: String, override val contentVersion: String, override val activityId: String,
        val frame: FrameId = FrameId.CLASSIC, override val rulesVersion: Int = PRACTICE_RULES_VERSION) : ActivityRef
    @Serializable @SerialName("game-stage")
    data class Game(override val contentId: String, override val contentVersion: String, override val activityId: String,
        override val rulesVersion: Int = MechanicRegistry.VERSION) : ActivityRef

    fun key(profileId: String) = ActivityKey(profileId, when(this) {
        is Tutor -> ActivityKind.TUTOR
        is Practice -> ActivityKind.PRACTICE
        is Game -> ActivityKind.GAME
    }, contentId, contentVersion, activityId, rulesVersion)
}

@Serializable
data class PathNode(val nodeId: String, val title: String, val activity: ActivityRef,
    val prerequisites: List<String> = emptyList(), val minutes: Int = 3, val evidence: List<EvidenceRef> = emptyList(),
    val chapter: String? = null)

@Serializable
data class LearningPath(val pathId: String, val pathVersion: String, val spaceId: String, val title: String, val language: String,
    val nodes: List<PathNode>, val excludedMembers: List<SpaceMember> = emptyList(), val schema: String = PATH_SCHEMA) {
    val dependencies get() = nodes.map { ContentVersion(it.activity.contentId, it.activity.contentVersion) }.toSet()

    fun validate() {
        ContentVersion(pathId, pathVersion).validate()
        requireContentId(spaceId)
        require(schema == PATH_SCHEMA && title.isNotBlank() && language.isNotBlank())
        require(nodes.size in 1..512)
        val seen = mutableSetOf<String>()
        for (node in nodes) {
            requireContentId(node.nodeId)
            require(node.title.isNotBlank() && node.minutes in 1..60)
            require(node.prerequisites.all { it in seen } && seen.add(node.nodeId)) { "Invalid node dependency or duplicate node" }
            val activity = node.activity
            val supported = when (activity) {
                // Legacy content and in-package activity IDs are opaque keys resolved against the installed v1 pack.
                is ActivityRef.Tutor -> TUTOR_RULES_VERSION
                is ActivityRef.Practice -> PRACTICE_RULES_VERSION
                is ActivityRef.Game -> {
                    ContentVersion(activity.contentId, activity.contentVersion).validate()
                    requireContentId(activity.activityId)
                    MechanicRegistry.VERSION
                }
            }
            require(activity.rulesVersion == supported) { "Unsupported activity rules" }
            node.evidence.forEach { it.validate() }
        }
        // Excluded members can also name legacy documents, including tagged document digests.
        excludedMembers.map { it.version() }.forEach { require(it.contentId.isNotBlank() && it.contentVersion.isNotBlank()) }
    }
}
