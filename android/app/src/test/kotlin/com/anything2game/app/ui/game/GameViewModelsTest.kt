package com.anything2game.app.ui.game

import com.anything2game.app.AndroidAppContainer
import com.anything2game.app.awaitIo
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.contract.game.*
import com.anything2game.contract.pack.PackOrigin
import com.anything2game.engine.game.GameRuntime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/** The shelf and the game keep stars, resume detection and ordering out of the screens, so they can be checked without drawing anything. */
@RunWith(RobolectricTestRunner::class)
class GameViewModelsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun until(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < end) { ShadowLooper.idleMainLooper(); if (condition()) return; Thread.sleep(5) }
        error("Timed out waiting for $what")
    }
    private fun container() = AndroidAppContainer(app).also { awaitIo { it.profiles.choose(LocalProfile.GUEST) } }
    private fun version(spec: GameSpec) = ContentVersion(spec.gameId, spec.gameVersion)

    private fun installed(spec: GameSpec, origin: PackOrigin = PackOrigin.PRESET, resume: Boolean = false, stars: Int = 0): InstalledGame {
        val deps = container()
        val device = awaitIo { deps.content.games().first { it.spec.gameId == spec.gameId } }
        return InstalledGame(device.manifest, device.spec, origin, resume, stars)
    }

    @Test fun `opening a game and leaving is not progress, a move is, finishing is not either`() {
        val spec = ReviewedGames.partition("en")
        val fresh = GameRuntime.start(spec, 7).checkpoint
        assertFalse("no moves yet", resumable(spec, fresh))
        assertFalse("never opened", resumable(spec, null))
        val moved = GameRuntime.apply(GameRuntime.start(spec, 7), GameAction.Help).checkpoint
        assertTrue(resumable(spec, moved))
    }

    @Test fun `the shelf shows the app's language, puts the game in progress first, then the last played, then the catalogue order`() {
        val locker = installed(ReviewedGames.partition("en"))
        val workshop = installed(ReviewedGames.allocation("en"))
        val bisect = installed(ReviewedGames.all("en").first { it.gameId.startsWith("git-bisect") })
        val chinese = installed(ReviewedGames.partition("zh"))
        val all = listOf(bisect, workshop, locker, chinese)
        assertEquals("catalogue order", listOf(locker, workshop, bisect).map { it.spec.gameId }, shelf(all, "en", null).map { it.spec.gameId })
        assertEquals("the last played comes first", workshop.spec.gameId, shelf(all, "en", version(workshop.spec)).first().spec.gameId)
        assertEquals("a game in progress comes before the last played", bisect.spec.gameId,
            shelf(listOf(locker, workshop, bisect.copy(resume = true)), "en", version(workshop.spec)).first().spec.gameId)
        assertTrue("a game in the other language stays off the shelf", chinese.spec.gameId !in shelf(all, "en", null).map { it.spec.gameId })
        assertTrue("unless it is being resumed", chinese.spec.gameId in shelf(listOf(chinese.copy(resume = true)), "en", null).map { it.spec.gameId })
    }

    @Test fun `the shelf reads every game with its stars and whether it can be resumed`() {
        val deps = container()
        val spec = ReviewedGames.allocation("en")
        awaitIo {
            deps.learningProgress.start(spec, 3)
            val run = deps.learningProgress.checkpoint(version(spec))!!
            deps.learningProgress.apply(spec, run, GameAction.Help)
            deps.learningProgress.recordStars(version(ReviewedGames.partition("en")), 2)
        }
        val shelf = PlaygroundViewModel.of(deps)
        assertNull("not read yet", shelf.games)
        shelf.load("en")
        until("the shelf") { shelf.games != null }
        val games = shelf.games!!
        assertEquals(ReviewedGames.all("en").size, games.size)
        assertEquals("the game in progress is first", spec.gameId, games.first().spec.gameId)
        assertTrue(games.first().resume)
        assertEquals(2, games.single { it.spec.gameId == ReviewedGames.partition("en").gameId }.stars)
        assertEquals(1, games.count { it.resume })
        shelf.leave()
        assertNull("what it showed is out of date once it leaves", shelf.games)
    }

    @Test fun `a game keeps its best stars through a fresh attempt and remembers it was the last one played`() {
        val deps = container()
        val spec = ReviewedGames.partition("en")
        val game = version(spec)
        awaitIo { deps.learningProgress.recordStars(game, 2) }
        val vm = GameViewModel.of(deps, game)
        until("the run and its stars") { vm.run != null && vm.bestStars == 2 }
        assertEquals(game, deps.homePreferences.lastGame)
        vm.load(replay = true)
        until("the fresh attempt") { !vm.busy && vm.run?.checkpoint?.actions?.isEmpty() == true }
        assertEquals("a new attempt never lowers it", 2, vm.bestStars)
        assertEquals(2, awaitIo { deps.learningProgress.bestStars(game) })
    }

    @Test fun `the quick look is seen once per game and per profile`() {
        val deps = container()
        val game = version(ReviewedGames.partition("en"))
        val vm = GameViewModel.of(deps, game)
        assertFalse(vm.introSeen())
        vm.markIntroSeen()
        assertTrue(GameViewModel.of(deps, game).introSeen())
        assertFalse(GameViewModel.of(deps, version(ReviewedGames.allocation("en"))).introSeen())
    }
}
