package edu.csi301.fieldnotes

data class Choice(val id: String, val text: String)
data class SourceExcerpt(val title: String, val location: String, val text: String)
data class Lesson(
    val revision: String,
    val nodeIds: Map<Step, String>,
    val title: String,
    val introduction: String,
    val reading: String,
    val question: String,
    val choices: List<Choice>,
    val correctId: String,
    val explanation: String,
    val source: SourceExcerpt,
    val takeaway: String,
)

fun interface LessonRepository {
    fun sample(): Result<Lesson>
}
