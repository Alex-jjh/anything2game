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

/** How far a student got in a scripted course's saved explanation (read from the cache without creating a session). */
data class TutorProgress(val explaining: Boolean, val position: Int, val total: Int) {
    /** The walk reached its last concept. */
    val finished: Boolean get() = explaining && position >= total - 1
}

interface TutorRepository {
    val endpoint: String
    val localServer: Boolean
    suspend fun open(id: String): OpenTutor
    suspend fun save(cache: TutorCache)
    suspend fun list(): List<TutorSession>
    /** Saved progress of a scripted course, or null when nothing was saved yet. Never creates or changes a session. */
    suspend fun progressFor(packageId: String): TutorProgress? = null
    suspend fun assess(cache: TutorCache, requestId: String): TutorSession
    suspend fun turn(cache: TutorCache, intent: TutorIntent, question: String, requestId: String, targetPosition: Int? = null): TutorSession
    suspend fun cancel(cache: TutorCache, requestId: String): TutorSession
    suspend fun upload(request: ImportRequest): DocumentResponse
    suspend fun preparePractice(session: TutorSession): LearningPackage
    suspend fun create(document: DocumentResponse, requestId: String): TutorSession
    /** Remembers what the reader wants out of the reading (kept on this device) and orders the questions by it. */
    suspend fun setFocus(cache: TutorCache, facets: List<Facet>, note: String): TutorCache = cache.withFocus(facets, note)
    /** The reader's own PDF of this document version with its text geometry, when this phone kept one; null shows the text. */
    suspend fun sourceDocument(documentId: String, documentVersion: String): SourceDocument? = null
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
    sourceDocumentsDir: File = File(context.applicationContext.filesDir, "source-documents"),
    private val http: TutorHttpClient = TutorHttpClient(context.applicationContext, prefsName = connectionPrefs),
    onRead: (suspend (TutorSession, Long, String) -> Unit)? = null,
) : TutorRepository {
    private val files = TutorCacheStore(sessionsDir, onRead)
    private val sourceFiles = SourceDocumentStore(sourceDocumentsDir)
    private val json = ContractJson.json
    override val endpoint get() = http.endpoint
    override val localServer get() = http.local

    /** A course with a pre-generated script, found by course id or by the key of its saved local session. */
    private suspend fun scripted(idOrKey: String): Triple<String, LearningPackage, TutorScript>? {
        if (idOrKey.startsWith("local-")) scripts.savedScript(idOrKey)?.let { (pkg, script) ->
            return Triple(idOrKey, pkg, script.validate(pkg))
        }
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
    internal suspend fun recoverPendingReads() = files.recoverPendingReads()
    override suspend fun progressFor(packageId: String): TutorProgress? {
        val pkg = packages.load(packageId).getOrNull() ?: return null
        val saved = try { files.read(localTutorKey(pkg)) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } ?: return null
        val s = saved.session
        return TutorProgress(explaining = s.messages.isNotEmpty(), position = s.conceptPosition, total = s.questions.size)
    }
    override suspend fun assess(cache: TutorCache, requestId: String): TutorSession {
        val s = cache.session
        if (s.mode == "local-mock") return s.copy(revision = s.revision + 1, answers = cache.draftAnswers,
            assessment = TutorRules.assess(localScript(s).blueprint, cache.draftAnswers))
        return decode(http.request("POST", "/v1/tutors/${s.sessionId}/assessment", json.encodeToString(AssessTutorRequest.serializer(), AssessTutorRequest(s.revision, cache.draftAnswers, requestId)))).keepingFocusOf(s)
    }
    override suspend fun turn(cache: TutorCache, intent: TutorIntent, question: String, requestId: String, targetPosition: Int?): TutorSession {
        val s = cache.session
        if (s.mode == "local-mock") {
            require(s.messages.size < 80)
            val position = TutorRules.positionAfter(s, intent, targetPosition)
            // Going back does not undo the walk: the furthest point reached is what the concept list offers.
            val next = s.copy(conceptPosition = position, conceptsIntroduced = maxOf(TutorRules.introduced(s), position + 1))
            // The student's own turn is logged in the course's language (an English course must not show Chinese in its Log).
            val zh = s.language == "zh"
            val text = when (intent) {
                TutorIntent.START -> if (zh) "按我的摸底结果开始讲解" else "Start from my results"
                TutorIntent.REPHRASE -> if (zh) "换个说法" else "Say it another way"
                TutorIntent.EXAMPLE -> if (zh) "举个例子" else "Give me an example"
                TutorIntent.CONTINUE -> if (zh) "继续讲解" else "Continue"
                TutorIntent.REVISIT -> if (position == 0 && s.conceptPosition > 0) (if (zh) "从头再讲一遍" else "Start over")
                    else if (position == s.conceptPosition) (if (zh) "这个概念再讲一遍" else "Explain this one again")
                    else if (zh) "回到上一个概念" else "Back to the concept before"
                TutorIntent.QUESTION -> question
            }
            return next.copy(revision = s.revision + 1, messages = s.messages + TutorMessage("m${s.revision + 1}u", "user", text) + TutorRules.scriptedReply(next, localScript(s).blueprint, intent))
        }
        return decode(http.request("POST", "/v1/tutors/${s.sessionId}/turns", json.encodeToString(TutorTurnRequest.serializer(), TutorTurnRequest(s.revision, intent, question, requestId, targetPosition)))).keepingFocusOf(s)
    }
    override suspend fun cancel(cache: TutorCache, requestId: String): TutorSession = if (cache.session.mode == "local-mock") cache.session else
        decode(http.request("POST", "/v1/tutors/${cache.session.sessionId}/cancel", json.encodeToString(CreateTutorRequest.serializer(), CreateTutorRequest(clientRequestId = requestId)))).keepingFocusOf(cache.session)
    /**
     * A PDF is read once, sent asking for its text geometry, and kept here with that geometry before the import goes on
     * (the import's retry path skips upload, so later would be never). The geometry goes no further: sessions don't need it.
     */
    override suspend fun upload(request: ImportRequest): DocumentResponse {
        if (request !is ImportRequest.PdfFile) return http.upload(request)
        val bytes = http.readPdf(request)
        val document = http.uploadPdf(request.title, bytes, layout = true)
        // Not keeping it (full disk, an older server without layout) only means "Show source" stays text; the import goes on.
        if (document.layout != null) try { sourceFiles.save(document, bytes) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        return document.copy(layout = null)
    }
    override suspend fun sourceDocument(documentId: String, documentVersion: String): SourceDocument? =
        try { sourceFiles.load(documentId, documentVersion) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
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
