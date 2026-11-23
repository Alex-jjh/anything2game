package com.anything2game.contract.game

/** Editorial summaries of the cited sections. Numerical conditions are original classroom examples. */
object ReviewedGames {
    fun partition(language: String): GameSpec {
        val zh = language.startsWith("zh", true)
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = EvidenceRef.Reviewed("ordered-halves", "binary-search", "1", "NIST Dictionary of Algorithms and Data Structures",
            "https://xlinux.nist.gov/dads/HTML/binarySearch.html", "Binary search · Definition",
            t("An ordered search can discard one half of its interval after each comparison.",
                "对有序区间进行比较后，可以排除其中一半，再在剩余区间继续查找。"),
            t("Bundled source summary checked against the NIST definition.", "内置知识卡依据 NIST 的定义核对。"))
        return GameSpec("locker-search-" + t("en", "zh"), "1", t("The locker search", "寻找神秘柜门"), t("en","zh"),
            listOf(card.cardId), listOf(card),
            t("Teaching setup: the hidden locker is fixed for this run. A query tells you which side contains it.",
                "教学设定：每局的目标柜门固定。一次询问可以确定目标位于分割点的哪一侧。"),
            listOf(GameStage("practice", t("Find the hidden locker among 16 with four questions.", "在 16 扇柜门中，用 4 次询问找到目标。"),
                PartitionConfig(16, 4), false, listOf(card.evidenceId)),
                GameStage("transfer", t("Try a new row of 12 lockers and a new hidden target.", "换一排 12 扇柜门，查找新目标。"),
                    PartitionConfig(12, 4), true, listOf(card.evidenceId))),
            setOf(MechanicRegistry.capability(MechanicRegistry.PARTITION)))
    }

    fun allocation(language: String): GameSpec {
        val zh = language.startsWith("zh", true)
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = EvidenceRef.Reviewed("two-goods", "opportunity-cost", "1", "Federal Reserve Bank of St. Louis",
            "https://www.stlouisfed.org/-/media/project/frbstl/stlouisfed/education/lessons/pdf/production-possibilities-frontier-powerpoint-lesson.pdf",
            "Production Possibilities Frontier · lesson steps 8–9",
            t("When all resources are in use on a two-good frontier, gaining more of one good costs some output of the other.",
                "在两种产品的生产可能性边界上，资源已全部投入；提高一种产品的产量，需要放弃另一种产品的部分产量。"),
            t("Bundled source summary checked against the lesson’s constant-cost example.", "内置知识卡依据课程中的固定机会成本示例核对。"))
        return GameSpec("workshop-" + t("en", "zh"), "1", t("Two workshops", "双工坊的取舍"), t("en","zh"),
            listOf(card.cardId), listOf(card),
            t("Teaching setup: each token runs one workshop. Yields and delivery targets are invented for this model.",
                "教学设定：一枚筹码只能分配给一个工坊。产能和交付目标均为本模型设定的数值。"),
            listOf(GameStage("practice", t("Deliver 6 bowls and 9 cups with six tokens.", "用 6 枚筹码交付 6 个碗、9 个杯子。"),
                AllocationConfig(6, 2, 3, 6, 9), false, listOf(card.evidenceId)),
                GameStage("transfer", t("With four tokens and changed yields, deliver 9 bowls and 2 cups.", "产能变了：用 4 枚筹码交付 9 个碗、2 个杯子。"),
                    AllocationConfig(4, 3, 2, 9, 2), true, listOf(card.evidenceId))),
            setOf(MechanicRegistry.capability(MechanicRegistry.ALLOCATION)))
    }

    fun all(language: String) = listOf(partition(language), allocation(language))
}
