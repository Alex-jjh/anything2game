package com.anything2game.server.tutor

import com.anything2game.contract.*
import com.anything2game.contract.tutor.*
import com.anything2game.server.api.ApiException
import com.anything2game.server.compile.GenerationException
import com.anything2game.server.llm.*
import com.anything2game.server.store.Store
import com.anything2game.server.store.StoredDocument
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

class TutorService(
    private val store: Store, private val compiler: LlmClient, private val teacher: LlmClient,
    private val mock: Boolean, private val timeoutMillis: Long,
) {
    private val json = ContractJson.json
    private val creationLocks = Array(32) { Mutex() }
    private fun conflict(code: String = "TUTOR_STALE") = ApiException(HttpStatusCode.Conflict, code, "The tutor changed. Reload before continuing.", true)
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun requestId(id: String) { if (!id.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) throw ApiException.badRequest("Invalid clientRequestId") }

    fun owned(id: String, owner: String): TutorRecord = store.getTutor(id)?.takeIf { it.ownerId == owner && store.getDocument(it.session.documentId)?.documentVersion == it.session.documentVersion }
        ?: throw ApiException.notFound()

    suspend fun create(doc: StoredDocument, req: CreateTutorRequest, consume: () -> Unit): TutorSession {
        requestId(req.clientRequestId)
        if (req.language !in setOf("zh", "en")) throw ApiException.badRequest("language must be zh or en")
        // One assessment per immutable document version and language. Repeated creation never bills again after success.
        val id = "tut_" + hash("${doc.documentId}:${doc.documentVersion}:${req.language}").take(40)
        return creationLocks[(id.hashCode() ushr 1) % creationLocks.size].withLock { createOnce(doc, req, consume, id) }
    }

    private suspend fun createOnce(doc: StoredDocument, req: CreateTutorRequest, consume: () -> Unit, id: String): TutorSession {
        store.getTutor(id)?.let { return owned(id, doc.ownerId).session }
        consume()
        // Deliberately bounded introductory coverage; never claims to assess the complete paper.
        val sources = doc.chunks.take(8).map { it.copy(text = it.text.take(2000)) }
        val bp = if (mock) fakeBlueprint(sources, req.language) else generate(sources, req.language)
        try { TutorRules.validate(bp, sources) } catch (_: Exception) { throw GenerationException.failed("The diagnostic did not pass source/option validation.") }
        val session = TutorSession(sessionId = id, documentId = doc.documentId, documentVersion = doc.documentVersion,
            title = doc.title, language = req.language, mode = if (mock) "mock" else "model", questions = bp.concepts.map { it.question }, sources = sources)
        currentCoroutineContext().ensureActive()
        return store.createTutor(TutorRecord(doc.ownerId, bp, session))?.session ?: throw ApiException.notFound()
    }

    fun assess(id: String, owner: String, req: AssessTutorRequest): TutorSession {
        val current = owned(id, owner)
        val fingerprint = hash(json.encodeToString(AssessTutorRequest.serializer(), req))
        replay(current, req.clientRequestId, fingerprint)?.let { return it }
        if (current.session.revision != req.expectedRevision) throw conflict()
        if (current.session.assessment.isNotEmpty()) throw conflict("TUTOR_ALREADY_ASSESSED")
        val evidence = try { TutorRules.assess(current.blueprint, req.answers) } catch (_: IllegalArgumentException) { throw ApiException.badRequest("Answer every question with a valid option or __unknown__.") }
        return commit(current, current.session.copy(revision = current.session.revision + 1, answers = req.answers, assessment = evidence), req.clientRequestId, fingerprint)
    }

    suspend fun turn(id: String, owner: String, req: TutorTurnRequest, consume: () -> Unit): TutorSession {
        val current = owned(id, owner)
        val fingerprint = hash(json.encodeToString(TutorTurnRequest.serializer(), req))
        replay(current, req.clientRequestId, fingerprint)?.let { return it }
        val s = current.session
        if (s.revision != req.expectedRevision) throw conflict()
        if (s.assessment.isEmpty()) throw ApiException.badRequest("Complete the diagnostic first.")
        if (s.messages.size >= 80) throw ApiException.badRequest("This tutorial has reached its 40-turn limit.")
        if (req.intent == TutorIntent.QUESTION && (req.question.isBlank() || req.question.length > 1000)) throw ApiException.badRequest("question must be 1 to 1000 characters")
        if (req.intent != TutorIntent.QUESTION && req.question.isNotEmpty()) throw ApiException.badRequest("Only QUESTION accepts text")
        if ((req.intent == TutorIntent.START) != s.messages.isEmpty()) throw ApiException.badRequest("START is only valid for the first turn.")
        val nextPosition = try { TutorRules.positionAfter(s, req.intent, req.targetPosition) }
        catch (e: IllegalArgumentException) {
            if (req.intent == TutorIntent.CONTINUE) throw ApiException.badRequest("All concepts have been introduced. Ask a follow-up instead.")
            throw ApiException.badRequest(e.message ?: "That concept cannot be explained again.")
        }
        consume()
        // Going back does not undo the walk: the furthest point reached is kept.
        val next = s.copy(conceptPosition = nextPosition, conceptsIntroduced = maxOf(TutorRules.introduced(s), nextPosition + 1))
        val user = TutorMessage("m${s.revision + 1}u", "user", if (req.intent == TutorIntent.QUESTION) req.question.trim() else label(req.intent, s.language))
        if (s.mode == "model" && mock) throw conflict("TUTOR_MODE_CHANGED")
        val answer = if (s.mode == "mock") TutorRules.scriptedReply(next, current.blueprint, req.intent) else reply(next, req)
        return commit(current, next.copy(revision = s.revision + 1, messages = s.messages + user + answer), req.clientRequestId, fingerprint)
    }

    fun cancel(id: String, owner: String, requestId: String): TutorSession {
        requestId(requestId)
        repeat(5) {
            val current = owned(id, owner)
            if (requestId in current.receipts || requestId in current.cancelled) return current.session
            if (current.cancelled.size >= 100) throw ApiException.badRequest("Too many cancelled requests")
            val next = current.copy(session = current.session.copy(revision = current.session.revision + 1), cancelled = current.cancelled + requestId)
            if (store.saveTutorIfRevision(next, current.session.revision)) return next.session
        }
        throw conflict()
    }

    private fun replay(current: TutorRecord, id: String, fingerprint: String): TutorSession? {
        requestId(id)
        if (id in current.cancelled) throw conflict("TUTOR_CANCELLED")
        current.receipts[id]?.let {
            if (it != fingerprint) throw ApiException.badRequest("clientRequestId already used for a different request")
            return current.session
        }
        return null
    }
    private fun commit(current: TutorRecord, session: TutorSession, id: String, fingerprint: String): TutorSession {
        if (store.saveTutorIfRevision(current.copy(session = session, receipts = current.receipts + (id to fingerprint)), current.session.revision)) return session
        val latest = owned(session.sessionId, current.ownerId)
        return replay(latest, id, fingerprint) ?: throw conflict()
    }

    private suspend fun call(client: LlmClient, prompt: RenderedPrompt, tokens: Int, label: String): String = try {
        withTimeout(timeoutMillis) { PromptFormat.restore(client.completeJson(LlmRequest(prompt.system, prompt.user, tokens, label))) }
    } catch (_: TimeoutCancellationException) { throw GenerationException(ErrorCodes.MODEL_TIMEOUT, "The tutor took too long. Retry the same request.", true) }
    catch (e: CancellationException) { throw e }
    catch (_: LlmException) { throw GenerationException.failed("The tutor provider could not answer.") }

    private suspend fun generate(sources: List<SourceChunk>, language: String): TutorBlueprint {
        val prompt = PromptTemplates.render("diagnostic.v1", mapOf("language" to language, "document" to PromptFormat.documentBlock(sources)))
        val raw = call(compiler, prompt, 4000, "diagnostic")
        return try { json.decodeFromString(TutorBlueprint.serializer(), JsonText.extractObject(raw)) }
        catch (_: Exception) { throw GenerationException.failed("Invalid diagnostic JSON") }
    }

    @Serializable private data class ModelReply(val text: String, val sourceRefs: List<SourceRef>, val grounded: Boolean)
    private suspend fun reply(s: TutorSession, req: TutorTurnRequest): TutorMessage {
        val q = TutorRules.orderedQuestions(s)[s.conceptPosition]
        val context = buildJsonObject {
            put("language", s.language); put("intent", req.intent.name); put("question", req.question)
            put("currentConcept", q.concept)
            put("assessment", json.encodeToJsonElement(s.assessment))
            put("questionSet", json.encodeToJsonElement(s.questions))
            put("responses", json.encodeToJsonElement(s.answers))
            put("history", json.encodeToJsonElement(s.messages.takeLast(12)))
        }.toString()
        val prompt = PromptTemplates.render("tutor.v1", mapOf("context" to context, "document" to PromptFormat.documentBlock(s.sources)))
        val raw = call(teacher, prompt, 1200, "tutor_turn")
        val reply = try { json.decodeFromString(ModelReply.serializer(), JsonText.extractObject(raw)) }
        catch (_: Exception) { throw GenerationException.failed("Invalid tutor JSON") }
        val valid = reply.grounded && reply.text.isNotBlank() && reply.text.length <= 3000 && runCatching { TutorRules.checkRefs(reply.sourceRefs, s.sources) }.isSuccess
        // Reject the entire answer if any quote is fabricated; a real quote alone still does not prove semantic correctness.
        val text = if (valid) reply.text else if (s.language == "zh") "现有文档依据不足，暂时不能可靠回答这个问题。可以回到原文核对或换一个问题。" else "The document does not provide enough evidence for a reliable answer. Check the source or ask another question."
        return TutorMessage("m${s.revision + 1}a", "tutor", text, if (valid) reply.sourceRefs else emptyList())
    }

    private fun label(intent: TutorIntent, language: String): String = if (language == "zh") when (intent) {
        TutorIntent.START -> "按我的摸底结果开始讲解"; TutorIntent.REPHRASE -> "换个说法"; TutorIntent.EXAMPLE -> "举个例子"; TutorIntent.CONTINUE -> "继续讲解"; TutorIntent.REVISIT -> "再讲一遍这个概念"; else -> "追问"
    } else when (intent) {
        TutorIntent.START -> "Start with my results"; TutorIntent.REPHRASE -> "Rephrase"; TutorIntent.EXAMPLE -> "Give an example"; TutorIntent.CONTINUE -> "Continue"; TutorIntent.REVISIT -> "Explain that one again"; else -> "Ask"
    }

    private fun fakeBlueprint(sources: List<SourceChunk>, language: String): TutorBlueprint {
        val zh = language == "zh"
        val excerpts = sources.flatMap { c -> c.text.split(Regex("(?<=[.!?。！？])\\s+")).filter { it.length >= 16 }.map { c to it.take(600) } }.take(3)
        if (excerpts.isEmpty()) throw GenerationException.failed("Not enough text for an introductory diagnostic.")
        return TutorBlueprint(concepts = excerpts.mapIndexed { i, (c, quote) ->
            val options = listOf(TutorOption("source", quote), TutorOption("unsupported", if (zh) "这段材料没有任何可阅读的文字。" else "The material contains no readable text."))
            TutorConcept(TutorQuestion("q${i + 1}", "concept${i + 1}", if (zh) "基础内容 ${i + 1}（mock）" else "Foundation ${i + 1} (mock)",
                if (zh) "哪一项出现在文档的这段基础内容中？" else "Which statement appears in this document?", if (i % 2 == 0) options else options.reversed(), listOf(SourceRef(c.chunkId, quote))),
                "source", if (zh) "文档原文写道：$quote\n这是规则生成的测试讲解，不是 AI 概念分析。" else "The source says: $quote\nThis is rule-based test teaching, not AI analysis.",
                if (zh) "先抓住这句话：$quote\nmock 模式只能引用原文，不能真正改写其含义。" else "Focus on this statement: $quote\nMock mode quotes rather than interprets it.",
                if (zh) "演示学习方法：先读这句话，合上文档，用自己的话复述，再对照原文。此例演示阅读动作，不是从文档推导的新事实。" else "A study example: read the statement, close the document, paraphrase, then compare. This demonstrates reading, not a new factual inference.")
        })
    }
}
