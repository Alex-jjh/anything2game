package com.anything2game.app.data.tutor

import android.content.Context
import com.anything2game.app.importing.ImportRequest
import com.anything2game.contract.*
import com.anything2game.contract.tutor.*
import com.anything2game.engine.ports.PackageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

const val FIXED_TUTOR_PACKAGE = "pkg_demo_retrieval"
class TutorUnavailable : Exception()
data class OpenTutor(val cache: TutorCache, val offline: Boolean = false)

interface TutorRepository {
    val endpoint: String
    val localServer: Boolean
    suspend fun open(id: String): OpenTutor
    suspend fun save(cache: TutorCache)
    suspend fun list(): List<TutorSession>
    suspend fun assess(cache: TutorCache, requestId: String): TutorSession
    suspend fun turn(cache: TutorCache, intent: TutorIntent, question: String, requestId: String): TutorSession
    suspend fun cancel(cache: TutorCache, requestId: String): TutorSession
    suspend fun upload(request: ImportRequest): DocumentResponse
    suspend fun preparePractice(session: TutorSession): LearningPackage
    suspend fun create(document: DocumentResponse, requestId: String): TutorSession
}

@Serializable data class FixedTutorial(val packageId: String, val contentVersion: String, val documentVersion: String, val blueprint: TutorBlueprint) {
    fun validate(pkg: LearningPackage): FixedTutorial {
        require(packageId == pkg.packageId && contentVersion == pkg.contentVersion && documentVersion == pkg.documentVersion) { "Fixed tutorial version mismatch" }
        TutorRules.validate(blueprint, pkg.sources)
        return this
    }
}

class AndroidTutorRepository(
    context: Context,
    private val packages: PackageRepository,
    private val installPackage: suspend (LearningPackage) -> Unit,
    sessionsDir: File = File(context.applicationContext.filesDir, "tutor-sessions"),
    connectionPrefs: String = "tutor_connection",
) : TutorRepository {
    private val app = context.applicationContext
    private val files = TutorCacheStore(sessionsDir)
    private val http = TutorHttpClient(app, prefsName = connectionPrefs)
    private val json = ContractJson.json
    override val endpoint get() = http.endpoint
    override val localServer get() = http.local

    private suspend fun fixed(): Pair<LearningPackage, FixedTutorial> {
        val pkg = packages.load(FIXED_TUTOR_PACKAGE).getOrThrow()
        val data = withContext(Dispatchers.IO) { app.assets.open("tutor/retrieval.draft.json").bufferedReader().use { it.readText() } }
        return pkg to json.decodeFromString(FixedTutorial.serializer(), data).validate(pkg)
    }
    override suspend fun open(id: String): OpenTutor {
        if (!id.startsWith("tut_")) {
            if (id != FIXED_TUTOR_PACKAGE && !id.startsWith("local-retrieval-")) throw TutorUnavailable()
            val (pkg, fixture) = fixed()
            // A new document/content version gets its own record; old self-ratings/progress are not reinterpreted.
            val key = "local-retrieval-${pkg.contentVersion}-${pkg.documentVersion}"
            val saved = files.read(key)
            val s = saved ?: TutorCache(TutorSession(sessionId = key, documentId = pkg.documentId, documentVersion = pkg.documentVersion,
                title = pkg.title, language = "zh", mode = "local-mock", questions = fixture.blueprint.concepts.map { it.question }, sources = pkg.sources))
            require(s.session.documentVersion == pkg.documentVersion && s.session.questions == fixture.blueprint.concepts.map { it.question })
            return OpenTutor(s)
        }
        val cached = files.read(id) // A corrupt record is retained, never silently replaced by a network response.
        return try {
            val s = decode(http.request("GET", "/v1/tutors/$id"))
            require(s.sessionId == id)
            if (cached != null) require(s.documentVersion == cached.session.documentVersion)
            val next = (cached?.copy(session = s) ?: TutorCache(s)).validate()
            files.write(next)
            OpenTutor(next)
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { cached?.let { OpenTutor(it, true) } ?: throw e }
    }
    override suspend fun save(cache: TutorCache) = files.write(cache)
    override suspend fun list() = files.list()
    override suspend fun assess(cache: TutorCache, requestId: String): TutorSession {
        val s = cache.session
        if (s.mode == "local-mock") return s.copy(revision = s.revision + 1, answers = cache.draftAnswers,
            assessment = TutorRules.assess(fixed().second.blueprint, cache.draftAnswers))
        return decode(http.request("POST", "/v1/tutors/${s.sessionId}/assessment", json.encodeToString(AssessTutorRequest.serializer(), AssessTutorRequest(s.revision, cache.draftAnswers, requestId))))
    }
    override suspend fun turn(cache: TutorCache, intent: TutorIntent, question: String, requestId: String): TutorSession {
        val s = cache.session
        if (s.mode == "local-mock") {
            require(s.messages.size < 80)
            val position = if (intent == TutorIntent.CONTINUE) s.conceptPosition + 1 else s.conceptPosition
            require(position in s.questions.indices)
            val next = s.copy(conceptPosition = position)
            val text = when (intent) {
                TutorIntent.START -> "按我的摸底结果开始讲解"; TutorIntent.REPHRASE -> "换个说法"; TutorIntent.EXAMPLE -> "举个例子"; TutorIntent.CONTINUE -> "继续讲解"; TutorIntent.QUESTION -> question
            }
            return next.copy(revision = s.revision + 1, messages = s.messages + TutorMessage("m${s.revision + 1}u", "user", text) + TutorRules.scriptedReply(next, fixed().second.blueprint, intent))
        }
        return decode(http.request("POST", "/v1/tutors/${s.sessionId}/turns", json.encodeToString(TutorTurnRequest.serializer(), TutorTurnRequest(s.revision, intent, question, requestId))))
    }
    override suspend fun cancel(cache: TutorCache, requestId: String): TutorSession = if (cache.session.mode == "local-mock") cache.session else
        decode(http.request("POST", "/v1/tutors/${cache.session.sessionId}/cancel", json.encodeToString(CreateTutorRequest.serializer(), CreateTutorRequest(clientRequestId = requestId))))
    override suspend fun upload(request: ImportRequest) = http.upload(request)
    override suspend fun create(document: DocumentResponse, requestId: String): TutorSession {
        val s = decode(http.request("POST", "/v1/documents/${document.documentId}/tutor", json.encodeToString(CreateTutorRequest.serializer(), CreateTutorRequest("zh", requestId))))
        require(s.documentId == document.documentId && s.documentVersion == document.documentVersion)
        return s
    }
    override suspend fun preparePractice(session: TutorSession): LearningPackage {
        require(session.mode != "local-mock")
        val request = CompileJobRequest(session.documentId, CompileOptions(language = session.language), java.util.UUID.randomUUID().toString())
        var status = json.decodeFromString(CompileJobStatus.serializer(), http.request("POST", "/v1/compile-jobs", json.encodeToString(CompileJobRequest.serializer(), request)))
        return kotlinx.coroutines.withTimeout(180_000) {
            while (!status.state.isTerminal) {
                kotlinx.coroutines.delay(700)
                status = json.decodeFromString(CompileJobStatus.serializer(), http.request("GET", "/v1/compile-jobs/${status.jobId}"))
            }
            val id = status.packageId
            if (status.state !in setOf(CompileJobState.READY, CompileJobState.PARTIAL) || id == null) throw TutorApiException(status.error?.code ?: "GENERATION_FAILED", true)
            val pkg = ContractJson.readValidPackage(http.request("GET", "/v1/packages/$id")).getOrThrow()
            require(pkg.packageId == id && pkg.documentId == session.documentId && pkg.documentVersion == session.documentVersion)
            installPackage(pkg)
            pkg
        }
    }
    private fun decode(raw: String): TutorSession = json.decodeFromString(TutorSession.serializer(), raw).also { TutorCache(it).validate() }
}
