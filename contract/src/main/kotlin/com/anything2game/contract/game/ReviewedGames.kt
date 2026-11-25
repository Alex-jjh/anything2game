package com.anything2game.contract.game

import java.util.Locale

/**
 * One reviewed game family: a mechanism, a concept and the words a student may type to ask for it. Both languages are built from
 * the same entry, so a topic never exists in one language only.
 */
class ReviewedGameEntry(val key: String, val mechanic: String, val concept: Pair<String, String>, val aliases: List<String>,
    private val build: (zh: Boolean) -> GameSpec) {
    fun spec(language: String) = build(language.startsWith("zh", true))
    fun conceptName(language: String) = if (language.startsWith("zh", true)) concept.second else concept.first
}

/**
 * Editorial summaries of the cited sections. Every game's numbers are original classroom setups, not measurements from its source;
 * each game says so in its teaching setup. All games share the two reviewed mechanisms and end with a changed-condition transfer.
 */
object ReviewedGames {
    private fun partitionCapability() = setOf(MechanicRegistry.capability(MechanicRegistry.PARTITION))
    private fun allocationCapability() = setOf(MechanicRegistry.capability(MechanicRegistry.ALLOCATION))

    fun partition(language: String): GameSpec = LOCKERS.spec(language)
    fun allocation(language: String): GameSpec = WORKSHOPS.spec(language)

    /** Every reviewed game for [language], the two original ones first (their ids are referenced by saved progress). */
    fun all(language: String): List<GameSpec> = catalog.map { it.spec(language) }

    /** The catalog entry [spec] was built from, or null for a game that is not one of them (generated, imported). Ids are "key-language". */
    fun entryFor(spec: GameSpec): ReviewedGameEntry? = catalog.firstOrNull { spec.gameId == it.key + "-" + spec.language }

    /** The best catalog entry for free text, or null when nothing in it is related. Deterministic and offline. */
    fun find(topic: String): ReviewedGameEntry? {
        val q = normalise(topic)
        if (q.isBlank()) return null
        val qTokens = q.split(' ').filter { it.length >= 3 }.toSet()
        var best: ReviewedGameEntry? = null
        var bestScore = 0
        for (entry in catalog) for (alias in entry.aliases.map(::normalise)) {
            val score = when {
                q == alias -> 100
                alias.length >= 2 && mentions(q, alias) -> 50 + alias.length
                q.length >= 3 && q !in AMBIGUOUS && q in alias -> 30
                alias.split(' ').any { it.length >= 3 && it !in AMBIGUOUS && it in qTokens } -> 20
                else -> 0
            }
            if (score > bestScore) { best = entry; bestScore = score }
        }
        return best
    }

    /** Words that mean something else in another field ("linear regression" is statistics, not a bug): never enough to pick a game on their own. */
    private val AMBIGUOUS = setOf("regression")

    /** A Latin alias must be a whole word (or its plural): "git" is not in "digital", "exam" is not in "example". Chinese has no spaces, so it stays a substring test. */
    private fun mentions(query: String, alias: String) =
        if (alias.all { it.code < 128 }) Regex("(^| )" + Regex.escape(alias) + "(e?s)?( |$)").containsMatchIn(query) else alias in query

    private fun normalise(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("[-_\\s]+"), " ")

    private fun card(id: String, cardId: String, title: String, url: String, section: String, fact: String, note: String) =
        EvidenceRef.Reviewed(id, cardId, "1", title, url, section, fact, note)

    private fun partitionGame(zh: Boolean, base: String, titleEn: String, titleZh: String, card: EvidenceRef.Reviewed,
        setupEn: String, setupZh: String, practice: Triple<String, String, PartitionConfig>,
        transfer: Triple<String, String, PartitionConfig>) = GameSpec(
        base + if (zh) "-zh" else "-en", "1", if (zh) titleZh else titleEn, if (zh) "zh" else "en",
        listOf(card.cardId), listOf(card), if (zh) setupZh else setupEn,
        listOf(GameStage("practice", if (zh) practice.second else practice.first, practice.third, false, listOf(card.evidenceId)),
            GameStage("transfer", if (zh) transfer.second else transfer.first, transfer.third, true, listOf(card.evidenceId))),
        partitionCapability(), intro = ReviewedIntros.of(base, zh), learn = ReviewedIntros.learn(base, zh))

    private fun allocationGame(zh: Boolean, base: String, titleEn: String, titleZh: String, card: EvidenceRef.Reviewed,
        setupEn: String, setupZh: String, practice: Triple<String, String, AllocationConfig>,
        transfer: Triple<String, String, AllocationConfig>) = GameSpec(
        base + if (zh) "-zh" else "-en", "1", if (zh) titleZh else titleEn, if (zh) "zh" else "en",
        listOf(card.cardId), listOf(card), if (zh) setupZh else setupEn,
        listOf(GameStage("practice", if (zh) practice.second else practice.first, practice.third, false, listOf(card.evidenceId)),
            GameStage("transfer", if (zh) transfer.second else transfer.first, transfer.third, true, listOf(card.evidenceId))),
        allocationCapability(), intro = ReviewedIntros.of(base, zh), learn = ReviewedIntros.learn(base, zh))

    // ---- the two original games: same ids, numbers, titles and texts as before, plus a presentation theme -----------------

    private val LOCKERS = ReviewedGameEntry("locker-search", MechanicRegistry.PARTITION, "Binary search" to "二分查找",
        listOf("binary search", "binary search algorithm", "ordered search", "information partition", "locker", "lockers", "halving",
            "二分搜索", "二分查找", "二分", "信息划分", "柜门")) { zh ->
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = EvidenceRef.Reviewed("ordered-halves", "binary-search", "1", "NIST Dictionary of Algorithms and Data Structures",
            "https://xlinux.nist.gov/dads/HTML/binarySearch.html", "Binary search · Definition",
            t("An ordered search can discard one half of its interval after each comparison.",
                "对有序区间进行比较后，可以排除其中一半，再在剩余区间继续查找。"),
            t("Bundled source summary checked against the NIST definition.", "内置知识卡依据 NIST 的定义核对。"))
        fun theme() = PartitionTheme(item = t("locker", "扇柜门"), items = t("lockers", "扇柜门"), target = t("hidden locker", "目标柜门"),
            query = t("questions", "次提问"), ask = t("Is it locker {n} or lower?", "目标在 {n} 号或之前吗？"),
            where = t("The hidden locker is one of lockers {from}–{to}.", "目标柜门在第 {from}–{to} 号之间。"), icon = "locker")
        GameSpec("locker-search-" + t("en", "zh"), "1", t("The locker search", "寻找神秘柜门"), t("en","zh"),
            listOf(card.cardId), listOf(card),
            t("Made-up numbers for practice. The hidden locker stays put for the whole round.",
                "数字是为练习编的。一轮里，藏起来的那扇柜门不会变。"),
            listOf(GameStage("practice", t("Find the hidden locker among 16. You win if you find it within 4 questions.", "在 16 扇柜门里找出藏起来的那一扇，4 次提问内找到就算赢。"),
                PartitionConfig(16, 4, theme()), false, listOf(card.evidenceId)),
                GameStage("transfer", t("A new row of 12 lockers and a new hidden one. Find it within 4 questions.", "换一排 12 扇柜门，藏的那扇也换了。同样 4 次提问内找到。"),
                    PartitionConfig(12, 4, theme()), true, listOf(card.evidenceId))),
            partitionCapability(), intro = ReviewedIntros.of("locker-search", zh), learn = ReviewedIntros.learn("locker-search", zh))
    }

    private val WORKSHOPS = ReviewedGameEntry("workshop", MechanicRegistry.ALLOCATION, "Opportunity cost" to "机会成本",
        listOf("opportunity cost", "opportunity costs", "resource allocation", "finite resource allocation", "production possibilities frontier",
            "workshop", "workshops", "trade off", "tradeoff", "scarcity", "机会成本", "资源分配", "有限资源配置", "有限资源分配", "生产可能性边界", "取舍", "权衡")) { zh ->
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = EvidenceRef.Reviewed("two-goods", "opportunity-cost", "1", "Federal Reserve Bank of St. Louis",
            "https://www.stlouisfed.org/-/media/project/frbstl/stlouisfed/education/lessons/pdf/production-possibilities-frontier-powerpoint-lesson.pdf",
            "Production Possibilities Frontier · lesson steps 8–9",
            t("When all resources are in use on a two-good frontier, gaining more of one good costs some output of the other.",
                "在两种产品的生产可能性边界上，资源已全部投入；提高一种产品的产量，需要放弃另一种产品的部分产量。"),
            t("Bundled source summary checked against the lesson’s constant-cost example.", "内置知识卡依据课程中的固定机会成本示例核对。"))
        fun theme() = AllocationTheme(firstZone = t("Workshop A", "工坊 A"), secondZone = t("Workshop B", "工坊 B"),
            firstGood = t("bowl", "个碗"), firstGoods = t("bowls", "个碗"), secondGood = t("cup", "个杯子"), secondGoods = t("cups", "个杯子"),
            token = t("helper", "名帮手"), tokens = t("helpers", "名帮手"), firstIcon = "bowl", secondIcon = "cup", tokenIcon = "worker")
        GameSpec("workshop-" + t("en", "zh"), "1", t("Two workshops", "双工坊的取舍"), t("en","zh"),
            listOf(card.cardId), listOf(card),
            t("Made-up numbers for practice. A helper can work in only one workshop.",
                "数字是为练习编的。每名帮手只能在一个工坊干活。"),
            listOf(GameStage("practice", t("Place 6 helpers so you make at least 6 bowls and 9 cups.", "把 6 名帮手分到两个工坊，至少做出 6 个碗和 9 个杯子。"),
                AllocationConfig(6, 2, 3, 6, 9, theme()), false, listOf(card.evidenceId)),
                GameStage("transfer", t("Fewer helpers and new speeds: make at least 9 bowls and 2 cups with 4 helpers.", "只剩 4 名帮手，速度也变了：至少做出 9 个碗和 2 个杯子。"),
                    AllocationConfig(4, 3, 2, 9, 2, theme()), true, listOf(card.evidenceId))),
            allocationCapability(), intro = ReviewedIntros.of("workshop", zh), learn = ReviewedIntros.learn("workshop", zh))
    }

    // ---- information partition: four new themes --------------------------------------------------------------------------

    private val BISECT = ReviewedGameEntry("git-bisect", MechanicRegistry.PARTITION, "git bisect" to "git bisect 二分排错",
        listOf("git bisect", "bisect", "debugging", "debug", "find the bug", "regression bug", "git", "bug", "二分排错", "调试", "排查", "回归缺陷", "提交")) { zh ->
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = card("bisect-halving", "git-bisect", "git-bisect · Git documentation", "https://git-scm.com/docs/git-bisect", "Description",
            t("git bisect runs a binary search over the commit history: given one known-good and one known-bad commit it picks a commit in between to test, and keeps narrowing the range until it reaches the first bad commit.",
                "git bisect 对提交历史做二分查找：给定一个已知正常和一个已知出错的提交，它每次挑中间的提交让你测试，不断缩小范围，直到找到第一个出错的提交。"),
            t("Editorial summary checked against the Description section of the manual.", "编辑摘要，依据手册 Description 一节核对。"))
        fun theme() = PartitionTheme(item = t("commit", "个提交"), items = t("commits", "个提交"), target = t("breaking commit", "出错的提交"),
            query = t("test runs", "次测试"), ask = t("Test commit {n}", "测试第 {n} 个提交"),
            where = t("The culprit is one of commits {from}–{to}.", "元凶在第 {from}–{to} 个提交里。"), icon = "commit")
        partitionGame(zh, "git-bisect", "Hunt the broken commit", "追捕搞砸构建的提交", card,
            "Made-up numbers. Like git bisect, we assume every commit before the bad one passes and every commit from it onward fails.",
            "数字是编的。和 git bisect 一样，我们假设出错提交之前的都通过，从它开始的都失败。",
            Triple("Find the commit that broke the build among 32, using no more than 5 test runs.", "构建变红了。在 32 个提交里，用不超过 5 次测试找出搞坏它的那一个。",
                PartitionConfig(32, 5, theme())),
            Triple("A shorter history: find the new culprit among 20 commits, using no more than 5 test runs.", "历史变短了：20 个提交，还是 5 次测试，元凶换了人。找出它。",
                PartitionConfig(20, 5, theme())))
    }

    private val NUMBER = ReviewedGameEntry("number-guess", MechanicRegistry.PARTITION, "High-low guessing" to "猜数字（高低）",
        listOf("guess the number", "guessing game", "number guessing", "high low", "higher lower", "guess", "猜数字", "猜数", "猜大小")) { zh ->
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = card("sorted-halves", "binary-search-algorithm", "Binary search algorithm · Wikipedia",
            "https://en.wikipedia.org/wiki/Binary_search_algorithm", "Procedure",
            t("Binary search compares the target with the middle of a sorted range; if they differ, the half that cannot contain the target is eliminated and the search continues in the other half.",
                "二分查找把目标与有序区间的中间值比较；若不相等，就排除不可能含有目标的那一半，在另一半中继续查找。"),
            t("Editorial summary of the Procedure section.", "编辑摘要，对应 Procedure 一节。"))
        fun theme() = PartitionTheme(item = t("number", "个数"), items = t("numbers", "个数"), target = t("secret number", "秘密数字"),
            query = t("guesses", "次猜测"), ask = t("Is it {n} or lower?", "是 {n} 或更小吗？"),
            where = t("The number is between {from} and {to}.", "秘密数字在 {from} 到 {to} 之间。"), icon = "number")
        partitionGame(zh, "number-guess", "High or low?", "高了还是低了？", card,
            "Made-up ranges. Your friend always answers truthfully, and the number stays the same for the whole round.",
            "范围是编的。朋友每次都说实话，而且一轮里数字不会变。",
            Triple("Find the secret number from 1 to 32 in 5 guesses or fewer.", "秘密数字在 1 到 32 之间，用不超过 5 次猜测找出它。", PartitionConfig(32, 5, theme())),
            Triple("A smaller range and fewer guesses: find the number from 1 to 12 in 4 guesses or fewer.", "范围变小，机会也变少：1 到 12，最多猜 4 次。", PartitionConfig(12, 4, theme())))
    }

    private val DICTIONARY = ReviewedGameEntry("dictionary", MechanicRegistry.PARTITION, "Searching a sorted list" to "在有序列表中查找",
        listOf("dictionary", "look up a word", "word lookup", "sorted list", "alphabetical", "alphabetical order", "lookup", "词典", "字典", "查词", "有序列表", "有序", "字母顺序")) { zh ->
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = card("sorted-lists", "binary-search-history", "Binary search algorithm · Wikipedia",
            "https://en.wikipedia.org/wiki/Binary_search_algorithm", "History",
            t("Keeping items in sorted order makes searching faster; the article traces this idea to antiquity and notes that the Latin dictionary Catholicon (1286) was the first work to describe rules for alphabetical order.",
                "把条目按顺序排列可以加快查找；文章把这一想法追溯到古代，并指出拉丁语词典 Catholicon（1286 年）是最早描述字母排序规则的著作。"),
            t("Editorial summary of the History section. The page counts are classroom numbers.", "编辑摘要，对应 History 一节；页数为课堂设定的数字。"))
        fun theme() = PartitionTheme(item = t("page", "页"), items = t("pages", "页"), target = t("page with the word", "目标页"),
            query = t("flips", "次翻页"), ask = t("Open page {n}", "翻到第 {n} 页"),
            where = t("The word is somewhere in pages {from}–{to}.", "要找的词在第 {from}–{to} 页。"), icon = "page")
        partitionGame(zh, "dictionary", "Find the word", "翻词典找词", card,
            "Made-up page counts for practice. Words are in alphabetical order, so every page tells you if your word comes before or after it.",
            "页数是编的。词按字母顺序排好，所以每翻一页，都能知道要找的词在它前面还是后面。",
            Triple("Find the page with today’s word in a 24-page dictionary, using 5 flips or fewer.", "词典有 24 页，用不超过 5 次翻页，找到今天要查的词在哪一页。",
                PartitionConfig(24, 5, theme())),
            Triple("A pocket dictionary: only 16 pages and 4 flips. Find the page with the word.", "换成 16 页的袖珍词典，最多翻 4 次，找到词在哪一页。", PartitionConfig(16, 4, theme())))
    }

    private val LAYERS = ReviewedGameEntry("layer-fault", MechanicRegistry.PARTITION, "Debugging a deep network" to "排查深层网络",
        listOf("layer", "layers", "deep network", "deep neural network", "neural network", "resnet", "residual network", "residual", "fault", "debug a network",
            "残差", "网络层", "神经网络", "深度网络", "深层网络", "排查网络")) { zh ->
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = card("sequential-depth", "resnet-depth", "Deep Residual Learning for Image Recognition (He et al., 2015)",
            "https://arxiv.org/abs/1512.03385", "Abstract",
            t("Residual networks are stacks of many layers applied in sequence; the paper evaluates depths of up to 152 layers, so a signal reaches each layer only after passing through all earlier ones.",
                "残差网络由许多依次作用的层堆叠而成，论文评估了最深 152 层的网络；信号要穿过前面所有层才能到达某一层。"),
            t("Checked against the abstract. The probe rule in the game is a teaching setup, not a claim of the paper.",
                "依据摘要核对。游戏中的探针规则是教学设定，并非论文的结论。"))
        fun theme() = PartitionTheme(item = t("layer", "层"), items = t("layers", "层"), target = t("faulty layer", "出错的那一层"),
            query = t("probes", "次探测"), ask = t("Probe layer {n}", "探测第 {n} 层"),
            where = t("The broken layer is one of layers {from}–{to}.", "出错的层在第 {from}–{to} 层。"), icon = "layer")
        partitionGame(zh, "layer-fault", "Which layer broke?", "哪一层坏了？", card,
            "Made-up depths. A probe reads clean if the fault is after that layer, and corrupted if the fault is at or before it.",
            "层数是编的。故障在该层之后，探测读数正常；故障在该层或更前面，读数异常。",
            Triple("A 16-layer network outputs noise. Find the first broken layer using 4 probes or fewer.", "一个 16 层的网络输出全是噪声。用不超过 4 次探测，找出最先出错的那一层。",
                PartitionConfig(16, 4, theme())),
            Triple("Now 32 layers and 5 probes: find the first broken layer.", "现在是 32 层，最多探测 5 次，找出最先出错的那一层。", PartitionConfig(32, 5, theme())))
    }

    // ---- finite resource allocation: four new themes --------------------------------------------------------------------

    private val ADVANTAGE = ReviewedGameEntry("comparative-advantage", MechanicRegistry.ALLOCATION, "Comparative advantage" to "比较优势",
        listOf("comparative advantage", "trade", "ricardo", "specialization", "specialisation", "gains from trade", "比较优势", "国际贸易", "专业化", "贸易")) { zh ->
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = card("lower-cost", "comparative-advantage", "Comparative advantage · Wikipedia",
            "https://en.wikipedia.org/wiki/Comparative_advantage", "Overview",
            t("A party has a comparative advantage in a good when it can produce it at a lower opportunity cost, meaning less of the other goods given up, than another party.",
                "一方生产某种产品时放弃的其他产品更少，即机会成本更低，就在该产品上具有比较优势。"),
            t("Editorial summary of the opening definition. The yields in the game are invented.", "编辑摘要，对应开头的定义；游戏中的产量均为虚构。"))
        fun theme() = AllocationTheme(firstZone = t("Vineyard", "葡萄园"), secondZone = t("Weaving hall", "织布坊"),
            firstGood = t("barrel of wine", "桶酒"), firstGoods = t("barrels of wine", "桶酒"), secondGood = t("bolt of cloth", "匹布"), secondGoods = t("bolts of cloth", "匹布"),
            token = t("worker", "名工人"), tokens = t("workers", "名工人"), firstIcon = "wine", secondIcon = "cloth", tokenIcon = "worker")
        allocationGame(zh, "comparative-advantage", "Wine or cloth?", "酿酒还是织布？", card,
            "Made-up numbers for practice. Each worker is either in the vineyard or at the looms.",
            "数字是为练习编的。每名工人要么在葡萄园，要么在织布坊。",
            Triple("Place 8 workers so you make at least 12 barrels of wine and 6 bolts of cloth.", "把 8 名工人分到葡萄园和织布坊，至少做出 12 桶酒和 6 匹布。", AllocationConfig(8, 3, 2, 12, 6, theme())),
            Triple("The neighbouring land has 6 workers with other skills: make at least 3 barrels of wine and 12 bolts of cloth.",
                "邻国只有 6 名工人，本事也不同：至少做出 3 桶酒和 12 匹布。", AllocationConfig(6, 1, 4, 3, 12, theme())))
    }

    private val REVISION = ReviewedGameEntry("exam-revision", MechanicRegistry.ALLOCATION, "Opportunity cost of time" to "时间的机会成本",
        listOf("revision", "exam", "exams", "study time", "time management", "studying", "revise", "复习", "考试", "时间管理", "学习时间")) { zh ->
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = card("hour-forgone", "opportunity-cost-time", "Opportunity cost · Wikipedia",
            "https://en.wikipedia.org/wiki/Opportunity_cost", "Overview",
            t("The opportunity cost of a choice is the value of the best alternative given up when resources are limited, so an hour spent on one subject is an hour not spent on another.",
                "资源有限时，一项选择的机会成本是被放弃的最佳替代方案的价值；用在一门课上的一小时，就不能再用在另一门课上。"),
            t("Editorial summary of the opening definition. The study yields are invented.", "编辑摘要，对应开头的定义；复习效率均为虚构。"))
        fun theme() = AllocationTheme(firstZone = t("Maths", "数学"), secondZone = t("History", "历史"),
            firstGood = t("question", "道题"), firstGoods = t("questions", "道题"), secondGood = t("page", "页笔记"), secondGoods = t("pages", "页笔记"),
            token = t("hour", "小时"), tokens = t("hours", "小时"), firstIcon = "scroll", secondIcon = "book", tokenIcon = "clock")
        allocationGame(zh, "exam-revision", "The revision planner", "复习时间怎么分", card,
            "Made-up numbers for practice. An hour can go to one subject only.",
            "数字是为练习编的。每个小时只能用在一门课上。",
            Triple("Use 7 hours to solve at least 12 maths questions and revise at least 8 history pages.", "用 7 小时，至少做 12 道数学题、看完 8 页历史笔记。",
                AllocationConfig(7, 4, 2, 12, 8, theme())),
            Triple("The exams moved up: 5 hours left, slower maths, faster history. Solve at least 4 questions and revise 9 pages.",
                "考试提前了：只剩 5 小时，数学变慢、历史变快。至少做 4 道题、看 9 页笔记。", AllocationConfig(5, 2, 3, 4, 9, theme())))
    }

    private val GPU = ReviewedGameEntry("gpu-budget", MechanicRegistry.ALLOCATION, "Compute budget trade-offs" to "算力预算的取舍",
        listOf("gpu", "gpus", "compute budget", "compute", "scaling law", "scaling laws", "chinchilla", "epochs", "transformer", "algorithm budget", "算力", "显卡", "预算", "训练预算", "缩放定律")) { zh ->
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = card("fixed-compute", "compute-optimal-training", "Training Compute-Optimal Large Language Models (Hoffmann et al., 2022)",
            "https://arxiv.org/abs/2203.15556", "Abstract",
            t("For a fixed compute budget, the authors find that model size and the number of training tokens should be scaled in equal proportion; putting everything into a bigger model leaves it undertrained.",
                "在算力预算固定时，作者发现模型规模与训练数据量应按相同比例增长；把预算全部用来加大模型，会使模型训练不足。"),
            t("Checked against the abstract. The yields in the game are invented and far simpler than real scaling.", "依据摘要核对。游戏中的产出为虚构，远比真实的缩放规律简单。"))
        fun theme() = AllocationTheme(firstZone = t("Go deeper", "加深网络"), secondZone = t("Train longer", "训练更久"),
            firstGood = t("layer", "层"), firstGoods = t("layers", "层"), secondGood = t("training pass", "轮训练"), secondGoods = t("training passes", "轮训练"),
            token = t("GPU hour", "个 GPU 小时"), tokens = t("GPU hours", "个 GPU 小时"), firstIcon = "layers", secondIcon = "clock", tokenIcon = "chip")
        allocationGame(zh, "gpu-budget", "The GPU budget", "GPU 预算怎么花", card,
            "Made-up numbers, much simpler than real training. A GPU hour goes either to a deeper network or to longer training.",
            "数字是编的，比真实训练简单得多。一个 GPU 小时，要么用来加深网络，要么用来训练更久。",
            Triple("Spend 10 GPU hours so you end up with at least 12 layers and 14 training passes.", "把 10 个 GPU 小时分出去，至少得到 12 层和 14 轮训练。", AllocationConfig(10, 4, 2, 12, 14, theme())),
            Triple("The cluster is shared: only 8 GPU hours left and new prices. Reach at least 6 layers and 15 training passes.",
                "集群要共用，只剩 8 个 GPU 小时，每小时的成果也变了：至少 6 层、15 轮训练。", AllocationConfig(8, 2, 3, 6, 15, theme())))
    }

    private val FARM = ReviewedGameEntry("farm-crops", MechanicRegistry.ALLOCATION, "Production possibilities" to "生产可能性",
        listOf("farm", "farming", "crop", "crops", "planting", "agriculture", "production possibility frontier", "production possibility", "ppf", "种地", "种植", "农场", "庄稼", "农业")) { zh ->
        fun t(en: String, cn: String) = if (zh) cn else en
        val card = card("frontier-curve", "production-possibility-frontier", "Production–possibility frontier · Wikipedia",
            "https://en.wikipedia.org/wiki/Production%E2%80%93possibility_frontier", "Overview",
            t("A production-possibility frontier shows the largest combinations of two goods that can be made with the available resources, so producing more of one good means producing less of the other.",
                "生产可能性边界表示用现有资源最多能生产的两种产品的组合，因此多生产一种产品，就必须少生产另一种。"),
            t("Editorial summary of the opening definition. The harvests are invented.", "编辑摘要，对应开头的定义；收成均为虚构。"))
        fun theme() = AllocationTheme(firstZone = t("Wheat field", "麦田"), secondZone = t("Corn field", "玉米地"),
            firstGood = t("sack of wheat", "袋小麦"), firstGoods = t("sacks of wheat", "袋小麦"), secondGood = t("crate of corn", "筐玉米"), secondGoods = t("crates of corn", "筐玉米"),
            token = t("plot", "块地"), tokens = t("plots", "块地"), firstIcon = "wheat", secondIcon = "corn", tokenIcon = "plot")
        allocationGame(zh, "farm-crops", "One farm, two crops", "一块地，两种庄稼", card,
            "Made-up harvests for practice. A plot grows either wheat or corn this season.",
            "收成是编的。每块地这一季只能种小麦或玉米。",
            Triple("Plant 9 plots to harvest at least 12 sacks of wheat and 10 crates of corn.", "把 9 块地分给小麦和玉米，至少收 12 袋小麦和 10 筐玉米。", AllocationConfig(9, 3, 2, 12, 10, theme())),
            Triple("A drought leaves 6 plots and new harvests: get at least 2 sacks of wheat and 12 crates of corn.",
                "干旱后只剩 6 块地，收成也变了：至少收 2 袋小麦和 12 筐玉米。", AllocationConfig(6, 1, 3, 2, 12, theme())))
    }

    /** The two original games come first; the rest alternate mechanisms so the first screen of tiles is varied. */
    val catalog: List<ReviewedGameEntry> = listOf(LOCKERS, WORKSHOPS, BISECT, ADVANTAGE, NUMBER, GPU, DICTIONARY, FARM, LAYERS, REVISION)
}
