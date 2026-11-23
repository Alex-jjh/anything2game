package com.anything2game.server.llm

import com.anything2game.contract.AskRequest
import com.anything2game.contract.Board
import com.anything2game.contract.BoardEdge
import com.anything2game.contract.BoardNode
import com.anything2game.contract.CastMember
import com.anything2game.contract.CastRole
import com.anything2game.contract.ContractJson
import com.anything2game.contract.CrossExamItem
import com.anything2game.contract.DeduceItem
import com.anything2game.contract.DeduceOption
import com.anything2game.contract.Item
import com.anything2game.contract.KnowledgeType
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.LearningUnit
import com.anything2game.contract.Level
import com.anything2game.contract.LevelOverlay
import com.anything2game.contract.Line
import com.anything2game.contract.Mechanic
import com.anything2game.contract.OrderItem
import com.anything2game.contract.OrderStep
import com.anything2game.contract.Recap
import com.anything2game.contract.Room
import com.anything2game.contract.SortBin
import com.anything2game.contract.SortCard
import com.anything2game.contract.SortItem
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceRef
import com.anything2game.contract.Statement
import com.anything2game.contract.SwipeCard
import com.anything2game.contract.SwipeItem
import com.anything2game.server.adapt.Retrieval
import com.anything2game.server.adapt.Sentences
import com.anything2game.server.compile.Plan
import com.anything2game.server.compile.PlannedLevel
import com.anything2game.server.compile.PlannedUnit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Offline model. Answers are derived deterministically from the chunks and the plan that are in the prompt, so every node's
 * output passes the validators: quotes are exact sentences of the chunks (Chinese text is split on 。！？ too).
 * It makes no network call and needs no key. It is the default provider, which is what lets the whole stack run in tests and demos.
 */
class FakeLlmClient(override val modelId: String = "fake-llm") : LlmClient {

    override suspend fun completeJson(request: LlmRequest): String {
        var label = request.label
        var prompt = request.user
        if (label.startsWith("repair:")) {
            // A repair prompt embeds the original task; answer that task again (the fake's first answer is already valid).
            label = label.removePrefix("repair:")
            prompt = PromptFormat.tagContent(request.user, "original_task") ?: request.user
        }
        return when (label.substringBefore(':')) {
            "activity-experience", "game-spec", "space-path" -> activityFixture(label,prompt)
            "plan" -> planJson(prompt)
            "level" -> levelJson(prompt)
            "frame" -> frameJson(label.substringAfter(':'), prompt)
            "ask" -> askJson(prompt)
            "claims" -> FakeLayered.claims(prompt)
            "claimcheck" -> FakeLayered.claimCheck(prompt)
            "planclaims" -> FakeLayered.planClaims(prompt)
            "unit" -> FakeLayered.unit(prompt)
            "support" -> FakeLayered.support(prompt)
            else -> throw LlmException(LlmException.Kind.BAD_RESPONSE, "fake model: unknown step '${request.label}'")
        }
    }

    private fun activityFixture(label: String, prompt: String): String {
        val input = json.parseToJsonElement(prompt) as kotlinx.serialization.json.JsonObject
        val reference = input.getValue("reference")
        return when (label) {
            "activity-experience" -> buildJsonObject {
                val zh = input["language"] == JsonPrimitive("zh")
                put("objective", if (zh) "依据知识卡完成挑战。" else "Apply the cited concept to the challenge.")
                put("action", if (zh) "尝试、观察并调整。" else "Try an action, observe, and adjust.")
                put("feedback", if (zh) "查看每步的结果。" else "Read the result after each step.")
                put("evidenceIds",input.getValue("evidenceIds"))
            }.toString()
            "space-path" -> {
                val path = json.decodeFromString<com.anything2game.contract.space.LearningPath>(reference.toString())
                json.encodeToString(path.copy(nodes = path.nodes.mapIndexed { i,node ->
                    node.copy(prerequisites = path.nodes.getOrNull(i-1)?.let { listOf(it.nodeId) }.orEmpty())
                }))
            }
            else -> reference.toString()
        }
    }

    private fun lang(prompt: String): String = PromptFormat.tagContent(prompt, "language")?.trim() ?: "en"
    private fun t(lang: String, en: String, zh: String) = if (lang == "zh") zh else en
    private val json = ContractJson.json

    // ---- plan -----------------------------------------------------------------------------------------------

    private fun planJson(prompt: String): String {
        val lang = lang(prompt)
        val chunks = PromptFormat.parseChunks(prompt)
        if (chunks.isEmpty()) throw LlmException(LlmException.Kind.BAD_RESPONSE, "fake model: no chunks in the prompt")
        val levelCount = ((chunks.size + 2) / 3).coerceIn(1, 4)
        val groups = chunks.chunked((chunks.size + levelCount - 1) / levelCount)
        val levels = groups.mapIndexed { li, group ->
            val sentences = group.sumOf { Sentences.split(it.text).size }
            val mechanics = if (sentences >= 3) listOf(Mechanic.SWIPE, Mechanic.SORT, Mechanic.DEDUCE, Mechanic.ORDER)
            else listOf(Mechanic.SWIPE, Mechanic.SORT, Mechanic.DEDUCE)
            val units = mechanics.mapIndexed { ui, mechanic ->
                val ids = when (mechanic) {
                    Mechanic.ORDER -> group.take(3).map { it.chunkId }
                    else -> listOf(group[ui % group.size], group[(ui + 1) % group.size]).map { it.chunkId }.distinct()
                }
                PlannedUnit(
                    unitId = "L${li + 1}U${ui + 1}",
                    knowledgeType = when (mechanic) {
                        Mechanic.SWIPE -> KnowledgeType.CLAIM
                        Mechanic.SORT -> KnowledgeType.CATEGORY
                        Mechanic.DEDUCE -> KnowledgeType.REASONING
                        else -> KnowledgeType.PROCESS
                    },
                    mechanic = mechanic,
                    chunkIds = ids,
                    mechanicReason = t(lang, "Fits this knowledge point.", "适合这个知识点。"),
                )
            }
            val head = Sentences.split(group.first().text).first().take(if (lang == "zh") 14 else 48).trim()
            PlannedLevel(
                title = "${li + 1}. $head",
                goal = t(lang, "Tell what the material says from what it does not.", "分清材料说了什么、没说什么。"),
                units = units,
            )
        }
        return json.encodeToString(Plan.serializer(), Plan(levels))
    }

    // ---- level ----------------------------------------------------------------------------------------------

    private fun levelJson(prompt: String): String {
        val lang = lang(prompt)
        val chunks = PromptFormat.parseChunks(prompt).associateBy { it.chunkId }
        val planText = PromptFormat.tagContent(prompt, "plan_level") ?: throw LlmException(LlmException.Kind.BAD_RESPONSE, "fake model: no plan in the prompt")
        val planned = json.decodeFromString(PlannedLevel.serializer(), planText)
        val units = planned.units.map { pu ->
            val cs = pu.chunkIds.mapNotNull { chunks[it] }.ifEmpty { chunks.values.toList() }
            val sentences = cs.flatMap { c -> Sentences.split(c.text).map { c to it.take(240) } }
            LearningUnit(
                unitId = pu.unitId, knowledgeType = pu.knowledgeType, mechanic = pu.mechanic, mechanicReason = pu.mechanicReason,
                item = item(pu.mechanic, sentences, lang, rot = 0, variant = false),
                hints = listOf(
                    t(lang, "Re-read the cited passage.", "回到引用的那段原文。"),
                    t(lang, "Look for the exact words in the passage.", "找找原文里的确切用词。"),
                ),
                variants = listOf(item(pu.mechanic, sentences, lang, rot = 1, variant = true)),
            )
        }
        val recap = planned.units.flatMap { it.chunkIds }.distinct().mapNotNull { chunks[it] }.take(3)
            .map { t(lang, "Remember: ", "记住：") + Sentences.split(it.text).first().take(120) }
        val level = Level(
            levelId = planned.levelId.ifBlank { "L1" }, title = planned.title, goal = planned.goal, units = units,
            recap = Recap(recap.ifEmpty { listOf(t(lang, "Review the material.", "回顾一下材料。")) }),
        )
        return json.encodeToString(Level.serializer(), level)
    }

    private fun item(mechanic: Mechanic, sentences: List<Pair<SourceChunk, String>>, lang: String, rot: Int, variant: Boolean): Item {
        val n = sentences.size
        fun s(i: Int) = sentences[(i + rot) % n]
        fun ref(p: Pair<SourceChunk, String>) = SourceRef(p.first.chunkId, p.second)
        val v = if (variant) "v" else ""
        return when (mechanic) {
            Mechanic.SWIPE -> SwipeItem(
                listOf(
                    SwipeCard("${v}s1", s(0).second, true, t(lang, "The passage says this directly.", "材料原文直接说了这一点。"), listOf(ref(s(0)))),
                    SwipeCard("${v}s2", t(lang, "It is not true that: ", "并非如此：") + s(1).second, false,
                        t(lang, "The passage says the opposite.", "材料说的恰恰相反。"), listOf(ref(s(1)))),
                    SwipeCard("${v}s3", s(2).second, true, t(lang, "The passage states this.", "材料明确写了这一点。"), listOf(ref(s(2)))),
                ),
            )
            Mechanic.SORT -> SortItem(
                prompt = t(lang, "File each statement.", "把每条陈述归类。"),
                bins = listOf(SortBin("in", t(lang, "In the material", "材料里有")), SortBin("out", t(lang, "Not in the material", "材料里没有"))),
                cards = listOf(
                    SortCard("${v}k1", s(0).second, "in"), SortCard("${v}k2", s(1).second, "in"),
                    SortCard("${v}k3", t(lang, "Something this material never says", "材料里从未说过的内容"), "out"),
                ),
                why = t(lang, "Only what the passage states belongs in the first bin.", "只有原文写到的内容才属于第一个分类。"),
                sourceRefs = listOf(ref(s(0))),
            )
            Mechanic.DEDUCE -> DeduceItem(
                scenario = s(0).second,
                prompt = t(lang, "Which conclusion does the material support?", "根据材料，哪个结论成立？"),
                options = listOf(
                    DeduceOption("${v}o1", s(1).second, true, t(lang, "This is what the passage states.", "这正是原文所述。")),
                    DeduceOption("${v}o2", t(lang, "The opposite of what the material says.", "与材料所述相反的说法。"), false,
                        t(lang, "The passage says otherwise.", "原文并非如此。")),
                    DeduceOption("${v}o3", t(lang, "Nothing can be concluded at all.", "什么结论也得不出。"), false,
                        t(lang, "The passage does support a conclusion.", "原文是支持某个结论的。")),
                ),
                sourceRefs = listOf(ref(s(1))),
            )
            Mechanic.ORDER -> {
                val steps = sentences.distinctBy { it.second }.take(4).let { if (variant) it.reversed().reversed() else it }
                OrderItem(
                    prompt = t(lang, "Put these in the order the material gives them.", "按材料中的顺序排列。"),
                    steps = steps.mapIndexed { i, p -> OrderStep("${v}p${i + 1}", p.second) },
                    why = t(lang, "That is the order of the passage.", "这就是原文的顺序。"),
                    sourceRefs = steps.map { ref(it) },
                )
            }
            Mechanic.CROSS_EXAM -> error("not a unit mechanic")
        }
    }

    // ---- frames ---------------------------------------------------------------------------------------------

    private fun frameJson(frame: String, prompt: String): String {
        val lang = lang(prompt)
        val pkg = ContractJson.decodePackage(PromptFormat.tagContent(prompt, "package") ?: throw LlmException(LlmException.Kind.BAD_RESPONSE, "fake model: no package"))
        val levelRefs = pkg.levels.associate { l -> l.levelId to l.units.flatMap { u -> u.item.allSourceRefs } }

        fun line(speaker: String, text: String, refs: List<SourceRef> = emptyList()) = Line(speaker, text, refs)
        fun statements(levelId: String, prefix: String): List<Statement> {
            val refs = levelRefs.getValue(levelId).distinctBy { it.quote }.ifEmpty { levelRefs.getValue(levelId) }
            val supported = (0 until 3).map { i ->
                val r = refs[i % refs.size]
                Statement("$prefix${i + 1}", r.quote, false, t(lang, "That is what the passage reports.", "这正是原文所述。"), listOf(r))
            }
            val r = refs[refs.size - 1]
            val flawed = Statement("${prefix}4", t(lang, "So this is proven for every case: ", "所以这在所有情况下都成立：") + r.quote, true,
                t(lang, "The material only says this much, not that it holds everywhere.", "材料只说了这些，并没有说在所有情况下都成立。"), listOf(r))
            return supported + flawed
        }
        fun crossExam(id: String, speaker: String, prompt: String, prefix: String, levelId: String) = LearningUnit(
            unitId = id, knowledgeType = KnowledgeType.CLAIM, mechanic = Mechanic.CROSS_EXAM,
            mechanicReason = t(lang, "Frame boss: spot the overclaim.", "框架 Boss：找出夸大的说法。"),
            item = CrossExamItem(speaker, prompt, statements(levelId, prefix)),
            hints = listOf(t(lang, "Which statement goes further than the passage?", "哪一条说得比原文更多？")),
        )

        val overlay = when (frame) {
            "case" -> {
                val cast = listOf(CastMember("mira", "Mira", CastRole.PARTNER), CastMember("summary", t(lang, "The Summary", "那份摘要"), CastRole.SUSPECT))
                val nodes = pkg.levels.flatMap { l -> l.units.map { u -> u to (levelRefs.getValue(l.levelId).firstOrNull { it.quote.isNotBlank() }) } }
                val board = Board(
                    nodes = pkg.levels.flatMap { it.units }.mapIndexed { i, u ->
                        BoardNode("n${i + 1}", u.item.allSourceRefs.firstOrNull()?.quote?.take(if (lang == "zh") 14 else 40) ?: u.unitId, u.unitId)
                    },
                    edges = (1 until nodes.size).map { BoardEdge("n$it", "n${it + 1}", t(lang, "leads to", "引出")) },
                )
                mapOf(
                    "cast" to json.encodeToJsonElement(ListSerializer(CastMember.serializer()), cast),
                    "levels" to json.encodeToJsonElement(ListSerializer(LevelOverlay.serializer()), pkg.levels.map { l ->
                        LevelOverlay(
                            levelId = l.levelId,
                            intro = listOf(
                                line("mira", t(lang, "New case: ${l.title}", "新案件：${l.title}")),
                                line("summary", t(lang, "No need to read it. I've already summarised it for you.", "不用读原文了，我已经替你总结好了。")),
                                line("mira", t(lang, "That worries me. Let's check the evidence.", "这正是我担心的。我们自己查证据。")),
                            ),
                            boss = crossExam("${l.levelId}BOSS", "summary", t(lang, "One claim goes beyond the evidence. Present the passage that shows it.", "有一条说法超出了证据。请出示能说明问题的原文。"), "st", l.levelId),
                            outro = listOf(line("mira", t(lang, "Case closed.", "结案。"))),
                        )
                    }),
                    "board" to json.encodeToJsonElement(Board.serializer(), board),
                )
            }
            "escape" -> {
                val cast = listOf(CastMember("archive", t(lang, "The Archive", "档案室"), CastRole.NARRATOR))
                mapOf(
                    "cast" to json.encodeToJsonElement(ListSerializer(CastMember.serializer()), cast),
                    "levels" to json.encodeToJsonElement(ListSerializer(LevelOverlay.serializer()), pkg.levels.map { l ->
                        val refs = levelRefs.getValue(l.levelId).distinctBy { it.quote }
                        val lock = if (refs.size >= 3) LearningUnit(
                            "${l.levelId}LOCK", KnowledgeType.PROCESS, Mechanic.ORDER, t(lang, "Frame boss: rebuild the sequence.", "框架 Boss：还原顺序。"),
                            OrderItem(t(lang, "The exit lock wants these in the right order.", "出口的锁需要正确的顺序。"),
                                refs.take(4).mapIndexed { i, r -> OrderStep("p${i + 1}", r.quote) }, t(lang, "That is the order of the passage.", "这是原文的顺序。"), refs.take(4)),
                            hints = listOf(t(lang, "What comes first in the passage?", "原文里最先出现什么？")),
                        ) else {
                            val r = refs.firstOrNull() ?: levelRefs.getValue(l.levelId).first()
                            LearningUnit(
                                "${l.levelId}LOCK", KnowledgeType.REASONING, Mechanic.DEDUCE, t(lang, "Frame boss: conclude.", "框架 Boss：下结论。"),
                                DeduceItem(r.quote, t(lang, "Which conclusion opens the lock?", "哪个结论能打开锁？"), listOf(
                                    DeduceOption("o1", r.quote, true, t(lang, "This is what the passage says.", "这是原文所述。")),
                                    DeduceOption("o2", t(lang, "The opposite.", "恰恰相反。"), false, t(lang, "The passage says otherwise.", "原文并非如此。")),
                                ), listOf(r)),
                                hints = listOf(t(lang, "Go back to the passage.", "回到原文。")),
                            )
                        }
                        LevelOverlay(
                            levelId = l.levelId,
                            intro = listOf(line("archive", t(lang, "The door locks behind you. Each room opens when you understand one part.", "门在身后锁上了。理解一部分，才能打开下一个房间。"))),
                            rooms = l.units.mapIndexed { i, u ->
                                Room("${l.levelId}R${i + 1}", t(lang, "Room ${i + 1}", "第${i + 1}个房间"), t(lang, "A locked room about ${u.unitId}.", "与 ${u.unitId} 有关的上锁房间。"), listOf(u.unitId))
                            },
                            boss = lock,
                            outro = listOf(line("archive", t(lang, "The door clicks open.", "门开了。"))),
                        )
                    }),
                )
            }
            "rookie" -> {
                val cast = listOf(CastMember("sam", "Sam", CastRole.ROOKIE))
                mapOf(
                    "cast" to json.encodeToJsonElement(ListSerializer(CastMember.serializer()), cast),
                    "levels" to json.encodeToJsonElement(ListSerializer(LevelOverlay.serializer()), pkg.levels.map { l ->
                        LevelOverlay(
                            levelId = l.levelId,
                            intro = listOf(line("sam", t(lang, "Hi! I'm new to this and my notes are a mess. Can you check them with me?", "你好！我刚接触这个，笔记很乱，能陪我一起检查吗？"))),
                            leadIns = l.units.associate { u -> u.unitId to listOf(line("sam", t(lang, "Hmm, I'm not sure about this one. Let's see.", "这一条我没把握，一起看看。"))) },
                            boss = crossExam("${l.levelId}NOTES", "sam", t(lang, "Sam's notes. One line is wrong. Show Sam the passage that proves it.", "Sam 的笔记。有一行是错的，请出示能证明的原文。"), "n", l.levelId),
                            outro = listOf(line("sam", t(lang, "Thanks! I'd have handed those notes in as they were.", "谢谢！我差点就这样交上去了。"))),
                        )
                    }),
                )
            }
            "companion" -> {
                val cast = listOf(CastMember("aya", "Aya", CastRole.COMPANION))
                mapOf(
                    "cast" to json.encodeToJsonElement(ListSerializer(CastMember.serializer()), cast),
                    "levels" to json.encodeToJsonElement(ListSerializer(LevelOverlay.serializer()), pkg.levels.map { l ->
                        LevelOverlay(
                            levelId = l.levelId,
                            intro = listOf(line("aya", t(lang, "I read this with you. Let's test it one idea at a time.", "我陪你读过了。我们一个想法一个想法地来。"))),
                            leadIns = l.units.associate { u ->
                                u.unitId to listOf(line("aya", t(lang, "Here is the part this is about.", "这一题讲的是这一段。"), u.item.allSourceRefs.take(1)))
                            },
                            outro = listOf(line("aya", t(lang, "Nice. That level is done.", "不错，这一关完成了。"))),
                        )
                    }),
                )
            }
            else -> throw LlmException(LlmException.Kind.BAD_RESPONSE, "fake model: unknown frame '$frame'")
        }
        return buildJsonObject { overlay.forEach { (k, v) -> put(k, v) } }.toString()
    }

    // ---- ask ------------------------------------------------------------------------------------------------

    private fun askJson(prompt: String): String {
        val lang = lang(prompt)
        val chunks = PromptFormat.parseChunks(prompt)
        val question = PromptFormat.tagContent(prompt, "question") ?: ""
        val best = Retrieval.top(question, chunks, 1).firstOrNull()
        if (best == null || best.score < 2) {
            return buildJsonObject {
                put("grounded", false)
                put("answer", t(lang, "The material does not answer this.", "材料里没有回答这个问题。"))
                put("sourceRefs", JsonArray(emptyList()))
            }.toString()
        }
        val sentence = Sentences.split(best.chunk.text).maxByOrNull { Retrieval.tokens(it).intersect(Retrieval.tokens(question)).size }!!.take(240)
        return buildJsonObject {
            put("grounded", true)
            put("answer", t(lang, "According to the material: ", "根据材料：") + sentence)
            put("sourceRefs", buildJsonArray {
                add(buildJsonObject { put("chunkId", best.chunk.chunkId); put("quote", sentence) })
            })
        }.toString()
    }
}
