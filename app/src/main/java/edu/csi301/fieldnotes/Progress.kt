package edu.csi301.fieldnotes

enum class Step { READING, QUESTION, FEEDBACK, SOURCE, REVIEW }

data class Progress(val step: Step = Step.READING, val answerId: String? = null) {
    fun toQuestion(): Progress =
        if (step == Step.READING) copy(step = Step.QUESTION) else this

    fun answer(choiceId: String, lesson: Lesson): Progress =
        if (step == Step.QUESTION && answerId == null && lesson.choices.any { it.id == choiceId })
            Progress(Step.FEEDBACK, choiceId)
        else this

    fun openSource(): Progress = if (step == Step.FEEDBACK) copy(step = Step.SOURCE) else this
    fun finish(): Progress = if (step == Step.SOURCE) copy(step = Step.REVIEW) else this

    fun encode(lesson: Lesson) = listOf(lesson.revision, "node:" + lesson.nodeIds.getValue(step),
        answerId.orEmpty()).joinToString("|")

    companion object {
        fun decode(raw: String?, lesson: Lesson): Progress? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 3 || parts[0] != lesson.revision) return null
            val location = parts[1]
            val step = if (location.startsWith("node:")) {
                lesson.nodeIds.entries.find { it.value == location.removePrefix("node:") }?.key
            } else {
                // Stage 1 wrote enum names for this exact version of the courtyard lesson.
                Step.entries.find { lesson.revision == "courtyard-v1" && it.name == location }
            } ?: return null
            val answer = parts[2].ifEmpty { null }
            val requiresAnswer = step in setOf(Step.FEEDBACK, Step.SOURCE, Step.REVIEW)
            if (requiresAnswer != (answer != null)) return null
            if (answer != null && lesson.choices.none { it.id == answer }) return null
            return Progress(step, answer)
        }
    }
}
