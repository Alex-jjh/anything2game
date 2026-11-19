package com.anything2game.app.data.tutor

import android.content.Context
import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.data.Familiarity
import com.anything2game.contract.ContractJson
import com.anything2game.contract.tutor.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TutorPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun `answers and conversation survive repository recreation and preserve old preferences`() = runBlocking {
        val deps = AndroidAppContainer(context)
        deps.homePreferences.setFamiliarity(EXAMPLE_TUTOR_PACKAGE, "1", "L1", Familiarity.KNOWN)
        deps.homePreferences.markPrepared(EXAMPLE_TUTOR_PACKAGE, "1")
        val oldPrefs = context.getSharedPreferences("home", Context.MODE_PRIVATE).all.toMap()
        val untouched = File(context.filesDir, "sessions/legacy-progress.json").also { it.parentFile!!.mkdirs(); it.writeText("legacy bytes") }
        val repo = deps.tutors
        var cache = repo.open(EXAMPLE_TUTOR_PACKAGE).cache
        cache = cache.copy(draftAnswers = cache.session.questions.associate { it.id to UNKNOWN_ANSWER }, questionIndex = 2)
        repo.save(cache)
        assertEquals(cache, AndroidAppContainer(context).tutors.open(EXAMPLE_TUTOR_PACKAGE).cache)
        cache = cache.copy(session = repo.assess(cache, "answers"))
        repo.save(cache)
        cache = cache.copy(session = repo.turn(cache, TutorIntent.START, "", "start"))
        repo.save(cache)
        assertEquals(cache, AndroidAppContainer(context).tutors.open(EXAMPLE_TUTOR_PACKAGE).cache)
        assertEquals("legacy bytes", untouched.readText())
        assertEquals(oldPrefs, context.getSharedPreferences("home", Context.MODE_PRIVATE).all)
        assertTrue(cache.session.messages.last().sourceRefs.isNotEmpty())
    }
    @Test fun `tutor script version mismatch is not migrated or silently accepted`() = runBlocking {
        val pkg = AndroidAppContainer(context).packages.load(EXAMPLE_TUTOR_PACKAGE).getOrThrow()
        val fixture = AndroidAppContainer(context).tutorScripts.scriptFor(EXAMPLE_TUTOR_PACKAGE)!!
        fixture.validate(pkg)
        assertThrows(IllegalArgumentException::class.java) { fixture.copy(contentVersion = "2").validate(pkg) }
        assertThrows(IllegalArgumentException::class.java) { fixture.copy(documentVersion = "different").validate(pkg) }
        Unit
    }
    @Test fun `corrupt and future cache remain byte intact on read failure`() = runBlocking {
        val initial = AndroidAppContainer(context).tutors.open(EXAMPLE_TUTOR_PACKAGE).cache
        val dir = File(context.cacheDir, "tutor-test")
        val store = TutorCacheStore(dir)
        store.write(initial)
        val file = dir.listFiles()!!.single { it.extension == "json" }
        for (bad in listOf("{broken", "{}", ContractJson.json.encodeToString(TutorCache.serializer(), initial).replace(TUTOR_SCHEMA, "future/schema"))) {
            file.writeText(bad)
            assertTrue(runCatching { store.read(initial.session.sessionId) }.isFailure)
            assertEquals(bad, file.readText())
        }
    }
    @Test fun `new document version record does not overwrite previous cache`() = runBlocking {
        val old = AndroidAppContainer(context).tutors.open(EXAMPLE_TUTOR_PACKAGE).cache
        val store = TutorCacheStore(File(context.cacheDir, "versioned-test"))
        store.write(old)
        val next = old.copy(session = old.session.copy(sessionId = "local-retrieval-2", documentVersion = "v2"))
        store.write(next)
        assertEquals(old, store.read(old.session.sessionId))
        assertEquals(next, store.read(next.session.sessionId))
    }

    private fun repo(dir: File) = AndroidAppContainer(context).let { AndroidTutorRepository(context, it.packages, it.tutorScripts, {}, dir) }
    private val retrievalKey = "local-retrieval-1-sha256:demo-retrieval-notes-v1"

    @Test fun `focus reorders the questions, is kept across reopen and does not drop anything`() = runBlocking {
        val dir = File(context.cacheDir, "focus-test"); dir.deleteRecursively()
        val repo = repo(dir)
        val fresh = repo.open(EXAMPLE_TUTOR_PACKAGE).cache
        assertEquals(listOf("foundation-1", "foundation-2", "foundation-3"), fresh.session.questions.map { it.id })
        assertTrue(TutorRules.carriesFacets(fresh.session.questions))
        assertFalse(fresh.focusChosen)
        val chosen = repo.setFocus(fresh, listOf(Facet.LIMITATION, Facet.RESULT), "  what is missing?  ")
        assertEquals(listOf("foundation-2", "foundation-3", "foundation-1"), chosen.session.questions.map { it.id })
        assertEquals(listOf(Facet.RESULT, Facet.LIMITATION), chosen.session.focus)
        assertEquals("what is missing?", chosen.session.focusNote)
        assertTrue(chosen.focusChosen)
        repo.save(chosen)
        assertEquals(chosen, repo(dir).open(EXAMPLE_TUTOR_PACKAGE).cache)
        // The teaching order follows the focus too.
        val answered = chosen.copy(draftAnswers = chosen.session.questions.associate { it.id to UNKNOWN_ANSWER })
        val assessed = answered.copy(session = repo.assess(answered, "a"))
        assertEquals(listOf("foundation-2", "foundation-3", "foundation-1"), TutorRules.orderedQuestions(assessed.session).map { it.id })
        // Too late once assessed; a long note is refused.
        assertThrows(IllegalArgumentException::class.java) { assessed.withFocus(listOf(Facet.METHOD), "") }
        assertThrows(IllegalArgumentException::class.java) { fresh.withFocus(emptyList(), "x".repeat(MAX_FOCUS_NOTE + 1)) }
        // "Let the explainer decide": nothing moves, but the question is not asked again.
        val decided = repo.setFocus(fresh, emptyList(), "")
        assertEquals(fresh.session.questions, decided.session.questions)
        assertTrue(decided.focusChosen && !focusAsk(decided))
    }
    private fun focusAsk(c: TutorCache) = !c.focusChosen && c.session.assessment.isEmpty() && c.draftAnswers.isEmpty()

    @Test fun `a session saved before facets existed still opens, now with the facets`() = runBlocking {
        val dir = File(context.cacheDir, "legacy-test"); dir.deleteRecursively()
        val current = repo(dir).open(EXAMPLE_TUTOR_PACKAGE).cache
        val legacy = current.copy(session = current.session.copy(questions = current.session.questions.map { it.copy(facet = null) }), draftAnswers = mapOf("foundation-1" to "recall"), questionIndex = 1)
        // Byte-level old format: no facet, focus, focusNote or focusChosen at all.
        val oldJson = ContractJson.json.encodeToString(TutorCache.serializer(), legacy)
            .replace(Regex(",\"focus\":\\[\\]"), "").replace(Regex(",\"focusNote\":\"\""), "").replace(Regex(",\"focusChosen\":false"), "")
        assertFalse(oldJson.contains("focus") || oldJson.contains("facet"))
        TutorCacheStore(dir).write(legacy)
        File(dir, dir.list()!!.single { it.endsWith(".json") }).writeText(oldJson)
        val opened = repo(dir).open(EXAMPLE_TUTOR_PACKAGE).cache
        assertEquals(setOf(Facet.BACKGROUND, Facet.RESULT, Facet.LIMITATION), opened.session.questions.mapNotNull { it.facet }.toSet())
        assertEquals(legacy.draftAnswers, opened.draftAnswers)
        assertEquals(1, opened.questionIndex)
        assertEquals(retrievalKey, opened.session.sessionId)
    }
}
