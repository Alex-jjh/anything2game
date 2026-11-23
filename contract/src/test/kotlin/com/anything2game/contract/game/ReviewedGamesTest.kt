package com.anything2game.contract.game

import com.anything2game.contract.ContractJson
import org.junit.Assert.*
import org.junit.Test

class ReviewedGamesTest {
    private val json = ContractJson.json

    @Test fun `every reviewed game exists in both languages, validates and has a real https source`() {
        for (language in listOf("en", "zh")) {
            val games = ReviewedGames.all(language)
            assertEquals(10, games.size)
            assertEquals("ids are unique", games.size, games.map { it.gameId }.toSet().size)
            for (game in games) {
                MechanicRegistry.validate(game)
                assertEquals(language, game.language)
                assertEquals(2, game.stages.size)
                assertTrue(game.stages.last().transfer)
                val card = game.evidenceRefs.single() as EvidenceRef.Reviewed
                assertTrue(card.sourceUrl.startsWith("https://"))
                assertTrue(game.teachingSetting.isNotBlank())
            }
        }
    }

    @Test fun `the two original games keep their ids and numbers`() {
        assertEquals("locker-search-en", ReviewedGames.partition("en").gameId)
        assertEquals("locker-search-zh", ReviewedGames.partition("zh").gameId)
        assertEquals("workshop-en", ReviewedGames.allocation("en").gameId)
        assertEquals("workshop-zh", ReviewedGames.allocation("zh").gameId)
        assertEquals(listOf(16, 12), ReviewedGames.partition("en").stages.map { (it.config as PartitionConfig).candidates })
        assertEquals(AllocationConfig(6, 2, 3, 6, 9), (ReviewedGames.allocation("en").stages[0].config as AllocationConfig).rulesOnly())
        assertEquals(AllocationConfig(4, 3, 2, 9, 2), (ReviewedGames.allocation("zh").stages[1].config as AllocationConfig).rulesOnly())
    }

    @Test fun `every theme names real icons, practice and transfer differ in rules and both languages share the rules`() {
        for ((en, zh) in ReviewedGames.all("en").zip(ReviewedGames.all("zh"))) {
            assertEquals(en.stages.map { it.config.rulesOnly() }, zh.stages.map { it.config.rulesOnly() })
            assertNotEquals(en.stages[0].config.rulesOnly(), en.stages[1].config.rulesOnly())
            for (stage in en.stages + zh.stages) when (val c = stage.config) {
                is PartitionConfig -> assertNotNull(c.theme?.icon)
                is AllocationConfig -> c.theme!!.let { assertNotNull(it.firstIcon); assertNotNull(it.secondIcon); assertNotNull(it.firstGoods) }
            }
        }
        assertEquals(5, ReviewedGames.catalog.count { it.mechanic == MechanicRegistry.PARTITION })
        assertEquals(5, ReviewedGames.catalog.count { it.mechanic == MechanicRegistry.ALLOCATION })
    }

    @Test fun `a theme survives a JSON round trip and an old payload without one still decodes`() {
        for (game in ReviewedGames.all("zh")) {
            val text = json.encodeToString(GameSpec.serializer(), game)
            assertEquals(game, json.decodeFromString(GameSpec.serializer(), text))
        }
        val old = """{"type":"partition","candidates":16,"queryBudget":4}"""
        assertEquals(PartitionConfig(16, 4), json.decodeFromString(StageConfig.serializer(), old))
        val oldAllocation = """{"type":"allocation","tokens":6,"firstYield":2,"secondYield":3,"firstGoal":6,"secondGoal":9}"""
        assertEquals(AllocationConfig(6, 2, 3, 6, 9), json.decodeFromString(StageConfig.serializer(), oldAllocation))
        // Nothing is written for an absent theme, so a game without one has exactly the bytes it always had.
        assertEquals(old, json.encodeToString(StageConfig.serializer(), PartitionConfig(16, 4)))
    }

    @Test fun `themes are validated and a theme alone does not make a transfer a changed condition`() {
        val game = ReviewedGames.partition("en")
        fun withIcon(icon: String) = game.copy(stages = game.stages.map { s ->
            s.copy(config = (s.config as PartitionConfig).copy(theme = (s.config as PartitionConfig).theme!!.copy(icon = icon))) })
        MechanicRegistry.validate(withIcon("page"))
        assertTrue(runCatching { MechanicRegistry.validate(withIcon("dragon")) }.isFailure)
        val noBrace = game.copy(stages = game.stages.map { s -> s.copy(config = (s.config as PartitionConfig).copy(theme = PartitionTheme(ask = "Ask"))) })
        assertTrue(runCatching { MechanicRegistry.validate(noBrace) }.isFailure)
        val long = game.copy(stages = game.stages.map { s -> s.copy(config = (s.config as PartitionConfig).copy(theme = PartitionTheme(item = "x".repeat(80)))) })
        assertTrue(runCatching { MechanicRegistry.validate(long) }.isFailure)
        val retheme = game.copy(stages = listOf(game.stages[0], game.stages[1].copy(config = (game.stages[0].config as PartitionConfig).copy(theme = PartitionTheme(icon = "page")))))
        assertTrue("same rules with another theme is not a changed condition", runCatching { MechanicRegistry.validate(retheme) }.isFailure)
        val badAllocation = ReviewedGames.allocation("en").let { g -> g.copy(stages = g.stages.map { s ->
            s.copy(config = (s.config as AllocationConfig).copy(theme = AllocationTheme(firstIcon = "dragon"))) }) }
        assertTrue(runCatching { MechanicRegistry.validate(badAllocation) }.isFailure)
    }

    @Test fun `free text finds the right preset in either language and nothing else`() {
        val cases = mapOf("git bisect" to "git-bisect", "How do I find the bug in my commits" to "git-bisect", "debugging" to "git-bisect",
            "guess the number" to "number-guess", "猜数字" to "number-guess", "Binary Search" to "locker-search", "二分查找" to "locker-search",
            "opportunity cost" to "workshop", "机会成本" to "workshop", "comparative advantage" to "comparative-advantage", "比较优势" to "comparative-advantage",
            "GPU budget" to "gpu-budget", "算力" to "gpu-budget", "resnet" to "layer-fault", "dictionary" to "dictionary", "查字典" to "dictionary",
            "farm crops" to "farm-crops", "种地" to "farm-crops", "exam revision" to "exam-revision", "复习计划" to "exam-revision")
        for ((topic, key) in cases) assertEquals(topic, key, ReviewedGames.find(topic)?.key)
        for (topic in listOf("orbital dynamics", "量子纠缠", "", "   ", "??", "digital twin", "an example of recursion", "default settings")) assertNull(topic, ReviewedGames.find(topic))
        assertEquals("git-bisect", ReviewedGames.find("my bugs")?.key)
    }

    @Test fun `regression names the bug game only as a bug, never as statistics`() {
        for (topic in listOf("regression bug", "a regression bug in my build", "回归缺陷")) assertEquals(topic, "git-bisect", ReviewedGames.find(topic)?.key)
        for (topic in listOf("linear regression", "Linear Regression model", "logistic regression", "regression", "线性回归", "回归分析"))
            assertNull(topic, ReviewedGames.find(topic))
    }
}
