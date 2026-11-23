package com.anything2game.server.activity

import com.anything2game.contract.*
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.*
import com.anything2game.contract.space.*
import com.anything2game.engine.game.GameRuntime
import com.anything2game.server.compile.GenerationException
import com.anything2game.server.compile.LlmSteps
import com.anything2game.server.llm.*
import com.anything2game.server.store.*
import com.anything2game.server.tutor.TutorService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class Experience(val objective: String, val action: String, val feedback: String, val evidenceIds: List<String>)

/** Reuses the compile tier for both planning and synthesis. Publication executes under the runner's
 * job lock; the caller orders it against Space edits and source deletion with the owner lock. */
class ActivityGeneration(
    private val client: LlmClient, private val store: Store, private val spaces: SpaceService, tutors: TutorService,
    private val fixture: Boolean, private val knowledge: KnowledgeSourceProvider = ReviewedKnowledge,
) {
    private val paths = PathSources(store, tutors)
    private val json = ContractJson.activityJson

    private data class Material(val language: String, val reference: JsonObject, val evidence: Set<String>,
        val game: GameSpec? = null, val path: LearningPath? = null, val excluded: List<SpaceMember> = emptyList())

    suspend fun generate(job: JobRecord, stage: (GenerationStage, List<SpaceMember>) -> Boolean, publish: ((JobRecord) -> JobRecord) -> Boolean) {
        val input = requireNotNull(job.generation).input
        if (!stage(GenerationStage.KNOWLEDGE, emptyList())) return
        val material = when (input) {
            is GenerationInput.ConceptGame -> {
                val sample = knowledge.reviewed(input) ?: throw GenerationException("UNSUPPORTED_TOPIC",
                    "No reviewed source is available for this topic. Try binary search or opportunity cost.", false)
                val game = sample.copy(gameId = job.packageId, gameVersion = job.jobId)
                Material(input.language, json.encodeToJsonElement(game).jsonObject, game.evidenceRefs.map { it.evidenceId }.toSet(), game = game)
            }
            is GenerationInput.SpacePath -> store.withOwnerLock(job.ownerId) {
                val space = currentSpace(job, input)
                val catalog = paths.collect(job.ownerId, space, input.language)
                val path = LearningPath(job.packageId, job.jobId, space.spaceId, space.title, input.language, catalog.nodes)
                Material(input.language, json.encodeToJsonElement(path).jsonObject, catalog.nodes.flatMap { it.evidence.map(EvidenceRef::evidenceId) }.toSet(),
                    path = path, excluded = catalog.excluded)
            }
        }
        if (material.path?.nodes?.isEmpty() == true) {
            if (!stage(GenerationStage.KNOWLEDGE, material.excluded)) return
            throw GenerationException("NO_AVAILABLE_ACTIVITIES",
                "Create a tutor or compile practice for an uploaded Space document first. Local packs stay on this device.", false)
        }
        if (!stage(GenerationStage.EXPERIENCE, material.excluded)) return
        val base = buildJsonObject {
            put("language", material.language)
            put("reference", material.reference)
            put("evidenceIds", json.encodeToJsonElement(material.evidence))
        }
        val experience = step("activity-experience", base, 1200) { raw ->
            json.decodeFromString<Experience>(raw).also { plan ->
                require(listOf(plan.objective, plan.action, plan.feedback).all { it.isNotBlank() && it.length <= 400 })
                require(plan.evidenceIds.isNotEmpty() && material.evidence.containsAll(plan.evidenceIds)) { "Unknown experience evidence" }
            }
        }
        val design = JsonObject(base + ("experience" to json.encodeToJsonElement(experience)))
        val pack = if (material.game != null) {
            val reference = material.game
            step("game-spec", design, 6000) { raw ->
                val game = json.decodeFromString<GameSpec>(raw)
                require(game.gameId == reference.gameId && game.gameVersion == reference.gameVersion && game.language == reference.language)
                require(game.conceptRefs == reference.conceptRefs && game.evidenceRefs == reference.evidenceRefs &&
                    game.requiredCapabilities == reference.requiredCapabilities) { "Use the supplied concept, capability and exact reviewed evidence." }
                require(game.title.length <= 120 && game.teachingSetting.length <= 600 && game.stages.all { it.goal.length <= 240 })
                GameRuntime.validate(game)
                PackCodec.build(game, PackOrigin.SERVER)
            }
        } else {
            val reference = requireNotNull(material.path)
            step("space-path", design, 12000) { raw ->
                val path = json.decodeFromString<LearningPath>(raw)
                require(path.pathId == reference.pathId && path.pathVersion == reference.pathVersion &&
                    path.spaceId == reference.spaceId && path.language == reference.language && path.excludedMembers.isEmpty())
                checkNodes(path, reference.nodes)
                val complete = path.copy(excludedMembers = material.excluded)
                PackCodec.build(complete, PackOrigin.SERVER)
            }
        }
        if (!stage(GenerationStage.VALIDATION, material.excluded)) return
        val bytes = PackCodec.toBytes(pack.copy(manifest = pack.manifest.copy(modelId = client.modelId, fixture = fixture)))
        PackCodec.readActivity(bytes.inputStream(), material.path?.dependencies.orEmpty(), validateGame = GameRuntime::validate).getOrThrow()
        if (!stage(GenerationStage.PACKAGE, material.excluded)) return
        store.withOwnerLock(job.ownerId) {
            if (input is GenerationInput.SpacePath) {
                val latest = paths.collect(job.ownerId, currentSpace(job, input), input.language)
                if (latest.nodes != material.path!!.nodes || latest.excluded != material.excluded)
                    throw GenerationException("SOURCE_CHANGED", "A source or activity changed while the path was being prepared. Try again.", true)
            }
            publish { locked ->
                store.writeArtifact(job.jobId, bytes)
                val kind = if (input is GenerationInput.SpacePath) ContentKind.PATH else ContentKind.GAME
                locked.copy(state = CompileJobState.READY, generation = locked.generation!!.copy(
                    result = GeneratedContent(ContentVersion(job.packageId, job.jobId), kind), excludedMembers = material.excluded,
                    modelId = client.modelId, fixture = fixture, publishedSources = material.path?.quotedDocuments()))
            }
        }
    }

    private fun currentSpace(job: JobRecord, input: GenerationInput.SpacePath): Space {
        val space = spaces.get(job.ownerId, input.spaceId)
        if (space.revision != input.expectedRevision) throw GenerationException("SPACE_STALE", "The Space changed. Reload before generating a path.", true)
        return space
    }

    private fun checkNodes(path: LearningPath, available: List<PathNode>) {
        path.validate()
        require(path.title.length <= 120 && path.nodes.size == available.size)
        val byId = available.associateBy { it.nodeId }
        path.nodes.forEach { node ->
            val source = requireNotNull(byId[node.nodeId]) { "Unknown activity node." }
            require(node.activity == source.activity && node.evidence == source.evidence) { "Unresolved activity or source evidence." }
            require(node.title.length <= 120 && (node.chapter?.length ?: 0) <= 120)
        }
    }

    private suspend fun <T> step(label: String, input: JsonObject, tokens: Int, validate: (String) -> T): T {
        val prompt = PromptTemplates.activity(label, input)
        var raw = LlmSteps.call(client, prompt, tokens, label)
        repeat(2) { attempt ->
            try {
                require(raw.length <= 160_000) { "Activity output exceeds size limit." }
                return validate(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                if (attempt == 1) throw GenerationException("INVALID_ACTIVITY", "The generated activity failed source or rule validation.", true)
                raw = LlmSteps.repair(client, prompt, raw, listOf((e.message ?: "Invalid output").take(320)), tokens, label)
            }
        }
        error("Unreachable")
    }
}
