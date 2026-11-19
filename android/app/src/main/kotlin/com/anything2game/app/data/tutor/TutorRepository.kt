package com.anything2game.app.data.tutor

import android.content.Context
import com.anything2game.app.importing.ImportRequest
import com.anything2game.contract.*
import com.anything2game.contract.tutor.*
import com.anything2game.engine.ports.PackageRepository
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/** The example the import screen offers as a stand-in; it is an ordinary scripted course like any other. */
const val EXAMPLE_TUTOR_PACKAGE = "pkg_demo_retrieval"
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
    /** Remembers what the reader wants out of the reading (kept on this device) and orders the questions by it. */
    suspend fun setFocus(cache: TutorCache, facets: List<Facet>, note: String): TutorCache = cache.withFocus(facets, note)
}

/** Key of the saved local session of a scripted course. The retrieval example keeps its original key so saved sessions survive. */
fun localTutorKey(pkg: LearningPackage): String =
    if (pkg.packageId == EXAMPLE_TUTOR_PACKAGE) "local-retrieval-${pkg.contentVersion}-${pkg.documentVersion}"
    else "local-${pkg.packageId}-${pkg.contentVersion}-${pkg.documentVersion}"

class AndroidTutorRepository(
    context: Context,
    private val packages: PackageRepository,
    private val scripts: TutorScripts,
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

    /** A course with a pre-generated script, found by course id or by the key of its saved local session. */
    private suspend fun scripted(idOrKey: String): Triple<String, LearningPackage, TutorScript>? {
        for (packageId in scripts.scriptedPackageIds()) {
            val script = scripts.scriptFor(packageId) ?: continue
            val pkg = packages.load(packageId).getOrNull() ?: continue
            val key = localTutorKey(pkg)
            if (idOrKey == packageId || idOrKey == key) return Triple(key, pkg, script.validate(pkg))
        }
        return null
    }
    private fun language(script: TutorScript) = if (script.blueprint.concepts.any { c -> c.question.prompt.any { it.code in 0x4E00..0x9FFF } }) "zh" else "en"

    override suspend fun open(id: String): OpenTutor {
        if (!id.startsWith("tut_")) {
            val (key, pkg, script) = scripted(id) ?: throw TutorUnavailable()
            // A new document/content version gets its own record; old self-ratings/progress are not reinterpreted.
            val base = script.blueprint.concepts.map { it.question }
            val saved = files.read(key)
            if (saved == null) return OpenTutor(TutorCache(TutorSession(sessionId = key, documentId = pkg.documentId, documentVersion = pkg.documentVersion,
                title = pkg.title, language = language(script), mode = "local-mock", questions = base, sources = pkg.sources)))
            // Same questions as the script (the script may have gained facet tags since the session was saved); order is the reader's focus.
            val byId = base.associateBy { it.id }
            val fresh = saved.session.questions.map { byId[it.id] ?: throw IllegalArgumentException("Saved question is not in the script") }
            require(saved.session.documentVersion == pkg.documentVersion && fresh.size == base.size && fresh.map { it.copy(facet = null) } == saved.session.questions.map { it.copy(facet = null) })
            return OpenTutor(saved.copy(session = saved.session.copy(questions = fresh)))
        }
        val cached = files.read(id) // A corrupt record is retained, never silently replaced by a network response.
        return try {
            val s = decode(http.request("GET", "/v1/tutors/$id")).keepingFocusOf(cached?.session)
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
            assessment = TutorRules.assess(localScript(s).blueprint, cache.draftAnswers))
        return decode(http.request("POST", "/v1/tutors/${s.sessionId}/assessment", json.encodeToString(AssessTutorRequest.serializer(), AssessTutorRequest(s.revision, cache.draftAnswers, requestId)))).keepingFocusOf(s)
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
            return next.copy(revision = s.revision + 1, messages = s.messages + TutorMessage("m${s.revision + 1}u", "user", text) + TutorRules.scriptedReply(next, localScript(s).blueprint, intent))
        }
        return decode(http.request("POST", "/v1/tutors/${s.sessionId}/turns", json.encodeToString(TutorTurnRequest.serializer(), TutorTurnRequest(s.revision, intent, question, requestId)))).keepingFocusOf(s)
    }
    override suspend fun cancel(cache: TutorCache, requestId: String): TutorSession = if (cache.session.mode == "local-mock") cache.session else
        decode(http.request("POST", "/v1/tutors/${cache.session.sessionId}/cancel", json.encodeToString(CreateTutorRequest.serializer(), CreateTutorRequest(clientRequestId = requestId)))).keepingFocusOf(cache.session)
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
    private suspend fun localScript(session: TutorSession): TutorScript = scripted(session.sessionId)?.third ?: throw TutorUnavailable()

    /** The focus lives on this device; the server does not know it, so each reply is put back in the reader's order. */
    private fun TutorSession.keepingFocusOf(old: TutorSession?): TutorSession =
        if (old == null || (old.focus.isEmpty() && old.focusNote.isEmpty())) this
        else copy(focus = old.focus, focusNote = old.focusNote, questions = TutorRules.orderByFocus(questions, old.focus))

    private fun decode(raw: String): TutorSession = json.decodeFromString(TutorSession.serializer(), raw).also { TutorCache(it).validate() }
}
