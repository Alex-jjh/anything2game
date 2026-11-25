package com.anything2game.contract.game

import com.anything2game.contract.game.IntroKind.*

/**
 * The "Quick look" of every reviewed game, in both languages: a hook question, a tiny story, the idea in one plain sentence, a
 * picture of how it works and how to play, in the style of a short knowledge video. Numbers in the stories are small everyday examples;
 * the claims about the idea itself are the ones on the game's reviewed source card.
 */
internal object ReviewedIntros {
    private class Scene(val kind: IntroKind, val en: Pair<String, String>, val zh: Pair<String, String>, val visual: String? = null)

    private fun hook(en: Pair<String, String>, zh: Pair<String, String>) = Scene(HOOK, en, zh)
    private fun story(en: Pair<String, String>, zh: Pair<String, String>) = Scene(STORY, en, zh)
    private fun idea(en: Pair<String, String>, zh: Pair<String, String>) = Scene(IDEA, en, zh)
    private fun how(en: Pair<String, String>, zh: Pair<String, String>, visual: String) = Scene(HOW_IT_WORKS, en, zh, visual)
    private fun play(en: Pair<String, String>, zh: Pair<String, String>) = Scene(HOW_TO_PLAY, en, zh)

    fun of(key: String, zh: Boolean): List<IntroScene> =
        (scenes[key] ?: emptyList()).map { IntroScene(it.kind, (if (zh) it.zh else it.en).first, (if (zh) it.zh else it.en).second, it.visual) }

    fun learn(key: String, zh: Boolean): String? = learnLines[key]?.let { if (zh) it.second else it.first }

    private val learnLines = mapOf(
        "locker-search" to ("Cut a search in half with every question." to "每问一个问题，就能排除一半。"),
        "workshop" to ("Making more of one thing means making less of another." to "多做一样，另一样就得少做。"),
        "git-bisect" to ("Find the one bad commit in just a few test runs." to "只测几次，就能找出出错的那个提交。"),
        "comparative-advantage" to ("Who should make what: the one who gives up less." to "谁该做什么：放弃得更少的那个人。"),
        "number-guess" to ("Guess any number fast by always asking the middle." to "总问中间那个数，就能很快猜中。"),
        "gpu-budget" to ("A fixed GPU budget: a bigger model or longer training?" to "GPU 时间有限：加大模型，还是训练更久？"),
        "dictionary" to ("Why sorted order lets you find a word in a few flips." to "为什么排好顺序，几次翻页就能找到词。"),
        "farm-crops" to ("One field, two crops: more of one means less of the other." to "一块地两种庄稼：多种一样，另一样就少。"),
        "layer-fault" to ("Find a broken layer by probing the middle." to "探测中间那一层，就能找出坏掉的层。"),
        "exam-revision" to ("An hour on one subject is an hour not spent on another." to "一小时用在一门课上，就不能用在另一门。"),
    )

    private val scenes: Map<String, List<Scene>> = mapOf(
        "locker-search" to listOf(
            hook("100 lockers, 1 prize" to "A prize is behind one of 100 lockers. How few yes-or-no questions do you need to find it?",
                "100 个柜子，1 个奖" to "奖品藏在 100 个柜子中的一个里。只能问“是不是”，最少要问几次？"),
            story("Guessing a locker" to "Sam picks a locker from 1 to 100. Mia asks: “50 or lower?” Yes. “25 or lower?” No. Now only 26 to 50 are left.",
                "猜柜号" to "小山选了 1 到 100 号柜子。小米问：“50 号或更小吗？”是。“25 号或更小吗？”不是。只剩 26 到 50 号。"),
            idea("Throw half away" to "When things are in order, one question can cross off half of what is left. Ask again on the rest.",
                "每次扔掉一半" to "东西排好了顺序，一个问题就能划掉剩下的一半。再对剩下的问一次。"),
            how("Half, then half again" to "16 lockers. One question leaves 8, then 4, then 2, then 1. Four questions are enough.",
                "一半，再一半" to "16 个柜子。问一次剩 8 个，再剩 4、2、1 个。4 次提问就够了。", IntroVisuals.HALVING),
            play("Your turn" to "Goal: find the hidden locker among 16.\nPlay: slide across the row to pick one, then ask.\nWin: find it in 4 questions. Round 2 has new numbers.\nStars: the fewer hints and wasted questions, the more stars.",
                "轮到你了" to "目标：在 16 扇柜门里找到目标\n玩法：滑动选一扇，再点提问\n过关：4 次提问内找到\n星星：提示用得越少、多问的次数越少，星星越多"),
        ),
        "workshop" to listOf(
            hook("More of both?" to "You have 6 helpers and two workshops, one for bowls and one for cups. Can you get more bowls without losing any cups?",
                "两样都多？" to "你有 6 名帮手和两个工坊，一个做碗，一个做杯子。想多做碗，杯子能一个都不少吗？"),
            story("Ana's workshops" to "Each helper makes 2 bowls in workshop A or 3 cups in workshop B. Send one more helper to A and you get 3 fewer cups.",
                "阿安的两个工坊" to "每名帮手在 A 坊做 2 个碗，或在 B 坊做 3 个杯子。多派一人去 A 坊，杯子就少 3 个。"),
            idea("Something has to give" to "When every helper is already working, making more of one thing means making less of the other. What you give up is the cost.",
                "有得就有失" to "人手都已经在干活时，多做一样，另一样就得少做。你放弃的东西，就是代价。"),
            how("Move one helper" to "Move one helper from cups to bowls: 2 more bowls, 3 fewer cups. Every move works like this.",
                "挪动一个人" to "把一名帮手从杯子挪到碗：多 2 个碗，少 3 个杯子。每次挪动都是这样。", IntroVisuals.TRADE),
            play("Your turn" to "Goal: make at least 6 bowls and 9 cups.\nPlay: drag helpers onto a workshop, then deliver.\nWin: reach both targets. Round 2 has new numbers.\nStars: the fewer hints and undos, the more stars.",
                "轮到你了" to "目标：至少做出 6 个碗和 9 个杯子\n玩法：把帮手拖进工坊，再交货\n过关：两个目标都达到\n星星：提示和撤销用得越少，星星越多"),
        ),
        "git-bisect" to listOf(
            hook("Who broke the build?" to "Green on Monday, red today, and 500 commits in between. How many test runs does it take to find the bad one?",
                "谁搞砸了构建？" to "周一还是绿的，今天变红了，中间有 500 个提交。最少要测几次才能找出出错的那个？"),
            story("Monday green, Friday red" to "Priya has 100 commits since the last green build. She tests the middle one: red. So the culprit is that commit or an earlier one.",
                "周一绿，周五红" to "小林上次构建正常后有 100 个提交。她测了中间那个：红的。所以元凶就是它，或者更早的提交。"),
            idea("Halve the history" to "git bisect picks a commit in the middle for you to test, then keeps narrowing the range until it finds the first bad commit.",
                "把历史对半砍" to "git bisect 会挑中间的提交让你测试，然后不断缩小范围，直到找出第一个出错的提交。"),
            how("Test the middle" to "16 commits. Test the middle one. Passes? The bug is later. Fails? It is that commit or earlier. Four tests are enough.",
                "测中间那个" to "16 个提交，先测中间的。通过了？问题在后面。失败了？就是它或更早的。4 次测试就够。", IntroVisuals.HALVING),
            play("Your turn" to "Goal: find the commit that broke the build.\nPlay: tap a commit to test it, then run the test.\nWin: find it within 5 test runs.\nStars: the fewer hints and wasted runs, the more stars.",
                "轮到你了" to "目标：找出搞坏构建的提交\n玩法：点一个提交，再点测试\n过关：5 次测试内找到\n星星：提示用得越少、多测的次数越少，星星越多"),
        ),
        "comparative-advantage" to listOf(
            hook("Why trade at all?" to "Two neighbours can each make both wine and cloth. Why would they still trade with each other?",
                "为什么要交换？" to "两个邻居都会酿酒，也都会织布。为什么他们还是要互相交换？"),
            story("Bread or bags" to "Lena makes 4 loaves or 2 bags an hour. Omar makes 2 loaves or 2 bags. A bag costs Lena 2 loaves but Omar only 1. Omar should sew.",
                "面包还是袋子" to "小莲一小时做 4 个面包或 2 个袋子，阿远做 2 个面包或 2 个袋子。做袋子，小莲少做 2 个面包，阿远只少 1 个。让阿远做。"),
            idea("Compare what you give up" to "Being the best is not the point. Whoever gives up less of the other thing to make it has the advantage.",
                "比一比放弃了什么" to "关键不是谁最强，而是谁做这件事时放弃得更少，谁就有优势。"),
            how("What a barrel costs" to "Move one worker from cloth to wine: 3 more barrels of wine, 2 fewer bolts of cloth. So 1 bolt costs 1.5 barrels.",
                "一桶酒值多少" to "把一名工人从织布挪去酿酒：多 3 桶酒，少 2 匹布。所以一匹布值 1.5 桶酒。", IntroVisuals.TRADE),
            play("Your turn" to "Goal: make at least 12 barrels of wine and 6 bolts of cloth.\nPlay: drag workers onto a place, then deliver.\nWin: reach both targets. Round 2 has new numbers.\nStars: the fewer hints and undos, the more stars.",
                "轮到你了" to "目标：至少 12 桶酒和 6 匹布\n玩法：把工人拖到一处，再交货\n过关：两个目标都达到\n星星：提示和撤销用得越少，星星越多"),
        ),
        "number-guess" to listOf(
            hook("Guess in 10 tries?" to "A friend picks a number from 1 to 1,000 and only says “higher” or “lower”. Can you find it in 10 guesses?",
                "10 次能猜中吗？" to "朋友心里想一个 1 到 1000 的数，只回答“大了”或“小了”。你能在 10 次内猜中吗？"),
            story("Higher or lower?" to "Guess 500: “Lower.” Guess 250: “Higher.” Two guesses, and 1,000 numbers have shrunk to about 250.",
                "大了还是小了？" to "猜 500：“小了。”猜 250：“大了。”才两次，1000 个数就缩到了 250 个左右。"),
            idea("Always ask the middle" to "Compare your guess with the middle of what is left. Whatever the answer, half the numbers are gone.",
                "总猜中间那个" to "拿中间的数去试。不管答案是什么，都能排除掉一半的数。"),
            how("Half, then half again" to "Numbers 1 to 16. Guess 8, and you keep 1 to 8 or 9 to 16. Then 4, then 2, then 1. Four guesses are enough.",
                "一半，再一半" to "1 到 16 的数。猜 8，就留下 1 到 8 或 9 到 16。再猜 4、2、1。4 次就够了。", IntroVisuals.HALVING),
            play("Your turn" to "Goal: find the secret number.\nPlay: tap a number to ask “is it that or lower?”\nWin: find it within 5 guesses.\nStars: the fewer hints and wasted guesses, the more stars.",
                "轮到你了" to "目标：猜出秘密数字\n玩法：点一个数，问“是它或更小吗？”\n过关：5 次猜测内猜中\n星星：提示用得越少、多猜的次数越少，星星越多"),
        ),
        "gpu-budget" to listOf(
            hook("Bigger or longer?" to "You have a fixed amount of GPU time. Do you spend it on a bigger model, or on training it for longer?",
                "加大，还是练久？" to "GPU 时间是固定的。你是拿它去加大模型，还是让模型训练得更久？"),
            story("A lab with a budget" to "A lab has 10 GPU hours. If it pours them all into a bigger model, the model ends up undertrained.",
                "预算有限的实验室" to "实验室只有 10 个 GPU 小时。如果全拿去加大模型，模型就会训练不足。"),
            idea("Split the budget" to "With a fixed budget, size and training time compete. Researchers found that growing both together works better than going all in on size.",
                "把预算分开花" to "预算固定时，模型大小和训练时间要抢资源。研究者发现，两者一起增长，比只加大模型更好。"),
            how("Move one GPU hour" to "Move one GPU hour from training to depth: 4 more layers, 2 fewer training passes. Real training is far more complicated.",
                "挪一个 GPU 小时" to "把一个 GPU 小时从训练挪去加深：多 4 层，少 2 轮训练。真实的训练要复杂得多。", IntroVisuals.TRADE),
            play("Your turn" to "Goal: reach at least 12 layers and 14 training passes.\nPlay: drag GPU hours onto a card, then deliver.\nWin: reach both targets. Round 2 has new numbers.\nStars: the fewer hints and undos, the more stars.",
                "轮到你了" to "目标：至少 12 层和 14 轮训练\n玩法：把 GPU 小时拖到一张卡上，再交货\n过关：两个目标都达到\n星星：提示和撤销用得越少，星星越多"),
        ),
        "dictionary" to listOf(
            hook("A word in 10 flips" to "How can you find any word in a 1,000-page dictionary in about ten flips?",
                "十次翻页找到词" to "一本 1000 页的词典，怎么才能翻十次左右，就找到任何一个词？"),
            story("Looking up “melon”" to "You open the dictionary in the middle and see “lemon”. “Melon” comes after “lemon”, so the whole front half is out.",
                "查“melon”" to "你翻开词典正中间，看到“lemon”。“melon”排在“lemon”后面，前半本整个可以不看了。"),
            story("An old idea" to "Keeping words in order is old. The Latin dictionary Catholicon, from 1286, was the first book to describe rules for alphabetical order.",
                "很老的办法" to "把词排好顺序是个老办法。1286 年的拉丁语词典 Catholicon，是第一本写下字母顺序规则的书。"),
            idea("Order makes it fast" to "Because words are in order, one look at a page tells you whether your word comes before it or after it.",
                "排好顺序就快" to "词是排好顺序的，所以看一眼某一页，就知道要找的词在它前面还是后面。"),
            how("Open the middle" to "16 pages. Open page 8: before or after? Keep that half, open its middle, and repeat. Four flips are enough.",
                "翻到中间" to "16 页。翻到第 8 页：词在前面还是后面？留下那一半，再翻它的中间。4 次翻页就够。", IntroVisuals.HALVING),
            play("Your turn" to "Goal: find the page that has today’s word.\nPlay: tap a page to open it, then flip.\nWin: find it within 5 flips.\nStars: the fewer hints and wasted flips, the more stars.",
                "轮到你了" to "目标：找到有今天这个词的那一页\n玩法：点一页，再点翻开\n过关：5 次翻页内找到\n星星：提示用得越少、多翻的次数越少，星星越多"),
        ),
        "farm-crops" to listOf(
            hook("Lots of both?" to "A farmer has 9 plots of land. Each plot grows wheat or corn, not both. Can she have plenty of both?",
                "两样都多？" to "农夫有 9 块地。每块地要么种小麦，要么种玉米，不能两样都种。她能两样都收很多吗？"),
            story("Two orders" to "A baker wants 12 sacks of wheat. A cook wants 10 crates of corn. Every plot given to wheat is a plot that can’t grow corn.",
                "两张订单" to "面包师要 12 袋小麦，厨师要 10 筐玉米。每块种了小麦的地，就不能再种玉米。"),
            idea("More here, less there" to "Once all your land is in use, more of one crop means less of the other. Economists call the best mixes the production-possibility frontier.",
                "多一样，少一样" to "地都用上之后，多收一种，另一种就得少收。经济学家把最好的搭配叫作“生产可能性边界”。"),
            how("Switch one plot" to "Switch one plot from corn to wheat: 3 more sacks of wheat, 2 fewer crates of corn.",
                "换一块地" to "把一块地从玉米换成小麦：多收 3 袋小麦，少收 2 筐玉米。", IntroVisuals.TRADE),
            play("Your turn" to "Goal: harvest at least 12 sacks of wheat and 10 crates of corn.\nPlay: drag plots onto a field, then deliver.\nWin: reach both targets. Round 2 has new numbers.\nStars: the fewer hints and undos, the more stars.",
                "轮到你了" to "目标：至少收 12 袋小麦和 10 筐玉米\n玩法：把地块拖进一块田，再交货\n过关：两个目标都达到\n星星：提示和撤销用得越少，星星越多"),
        ),
        "layer-fault" to listOf(
            hook("Which layer broke?" to "A network with 152 layers outputs garbage. How do you find the broken layer without checking all 152?",
                "哪一层坏了？" to "一个有 152 层的网络输出一团乱码。怎样不用把 152 层都查一遍，就找出坏的那层？"),
            story("Dirty tap water" to "Water flows through 16 pipes and comes out dirty. Test it after pipe 8: dirty. So the trouble is in pipe 8 or before it.",
                "水龙头出脏水" to "水依次流过 16 根水管，出来是脏的。在第 8 根后取样：脏的。所以问题在第 8 根或更前面。"),
            idea("Signals go in order" to "In a deep network, a signal passes through every layer in order. A bad reading at one layer points to that layer or an earlier one.",
                "信号依次通过" to "深层网络里，信号要依次穿过每一层。某一层读数异常，问题就在这一层或更前面。"),
            how("Probe the middle" to "16 layers. Probe layer 8. Clean? The fault is later. Corrupted? It is layer 8 or earlier. Four probes are enough.",
                "探测中间那层" to "16 层。探测第 8 层。正常？问题在后面。异常？就是第 8 层或更前面。4 次探测就够。", IntroVisuals.HALVING),
            play("Your turn" to "Goal: find the first broken layer.\nPlay: tap a layer to probe it, then probe.\nWin: find it within 4 probes.\nStars: the fewer hints and wasted probes, the more stars.",
                "轮到你了" to "目标：找出最先出错的那一层\n玩法：点一层，再点探测\n过关：4 次探测内找到\n星星：提示用得越少、多测的次数越少，星星越多"),
        ),
        "exam-revision" to listOf(
            hook("Seven hours, two exams" to "There are 7 hours left before two exams. Where should each hour go?",
                "7 小时，两场考试" to "离两场考试只剩 7 小时。每个小时该用在哪一科？"),
            story("Lin’s Friday night" to "Lin can do 4 maths questions or revise 2 history pages in an hour. An hour on maths is an hour history doesn’t get.",
                "小林的周五晚上" to "小林一小时能做 4 道数学题，或看 2 页历史笔记。花在数学上的一小时，历史就拿不到了。"),
            idea("The cost of a choice" to "The cost of a choice is the best thing you give up. Spend an hour on one subject and you give up that hour on the other.",
                "选择的代价" to "一个选择的代价，就是你放弃的最好的那样东西。把一小时给一门课，就放弃了给另一门课的那一小时。"),
            how("Move one hour" to "Move one hour from history to maths: 4 more questions, 2 fewer pages.",
                "挪一个小时" to "把一小时从历史挪给数学：多做 4 道题，少看 2 页笔记。", IntroVisuals.TRADE),
            play("Your turn" to "Goal: solve 12 maths questions and revise 8 history pages.\nPlay: drag hours onto a subject, then hand in.\nWin: reach both targets. Round 2 has new numbers.\nStars: the fewer hints and undos, the more stars.",
                "轮到你了" to "目标：做 12 道数学题，看 8 页历史笔记\n玩法：把小时拖给一门课，再交上\n过关：两个目标都达到\n星星：提示和撤销用得越少，星星越多"),
        ),
    )
}
