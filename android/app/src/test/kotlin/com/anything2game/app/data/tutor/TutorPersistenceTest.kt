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
        deps.homePreferences.setFamiliarity(FIXED_TUTOR_PACKAGE, "1", "L1", Familiarity.KNOWN)
        deps.homePreferences.markPrepared(FIXED_TUTOR_PACKAGE, "1")
        val oldPrefs = context.getSharedPreferences("home", Context.MODE_PRIVATE).all.toMap()
        val untouched = File(context.filesDir, "sessions/legacy-progress.json").also { it.parentFile!!.mkdirs(); it.writeText("legacy bytes") }
        val repo = deps.tutors
        var cache = repo.open(FIXED_TUTOR_PACKAGE).cache
        cache = cache.copy(draftAnswers = cache.session.questions.associate { it.id to UNKNOWN_ANSWER }, questionIndex = 2)
        repo.save(cache)
        assertEquals(cache, AndroidAppContainer(context).tutors.open(FIXED_TUTOR_PACKAGE).cache)
        cache = cache.copy(session = repo.assess(cache, "answers"))
        repo.save(cache)
        cache = cache.copy(session = repo.turn(cache, TutorIntent.START, "", "start"))
        repo.save(cache)
        assertEquals(cache, AndroidAppContainer(context).tutors.open(FIXED_TUTOR_PACKAGE).cache)
        assertEquals("legacy bytes", untouched.readText())
        assertEquals(oldPrefs, context.getSharedPreferences("home", Context.MODE_PRIVATE).all)
        assertTrue(cache.session.messages.last().sourceRefs.isNotEmpty())
    }
    @Test fun `fixed tutorial version mismatch is not migrated or silently accepted`() = runBlocking {
        val pkg = AndroidAppContainer(context).packages.load(FIXED_TUTOR_PACKAGE).getOrThrow()
        val fixture = context.assets.open("tutor/retrieval.draft.json").bufferedReader().use { ContractJson.json.decodeFromString(FixedTutorial.serializer(), it.readText()) }
        fixture.validate(pkg)
        assertThrows(IllegalArgumentException::class.java) { fixture.copy(contentVersion = "2").validate(pkg) }
        assertThrows(IllegalArgumentException::class.java) { fixture.copy(documentVersion = "different").validate(pkg) }
        Unit
    }
    @Test fun `corrupt and future cache remain byte intact on read failure`() = runBlocking {
        val initial = AndroidAppContainer(context).tutors.open(FIXED_TUTOR_PACKAGE).cache
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
        val old = AndroidAppContainer(context).tutors.open(FIXED_TUTOR_PACKAGE).cache
        val store = TutorCacheStore(File(context.cacheDir, "versioned-test"))
        store.write(old)
        val next = old.copy(session = old.session.copy(sessionId = "local-retrieval-2", documentVersion = "v2"))
        store.write(next)
        assertEquals(old, store.read(old.session.sessionId))
        assertEquals(next, store.read(next.session.sessionId))
    }
}
