package edu.csi301.fieldnotes

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import java.io.IOException
import java.io.Reader

const val SAMPLE_ASSET = "courtyard.mock.json"

class JsonLessonRepository(private val open: () -> Reader) : LessonRepository {
    override fun sample(): Result<Lesson> = try {
        Result.success(open().use { reader ->
            val root = gson.fromJson(reader, JsonElement::class.java)
            require(root != null && root.isJsonObject) { "缺少 JSON 对象" }
            parse(root.asJsonObject)
        })
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: JsonParseException) {
        Result.failure(error)
    } catch (error: IllegalArgumentException) {
        Result.failure(error)
    }

    private fun parse(root: JsonObject): Lesson {
        require(root.text("format") == "draft/mock-v1") { "不支持的示例格式" }
        val reading = root.obj("reading")
        val question = root.obj("question")
        val feedback = question.obj("feedback")
        val source = root.obj("source")
        val review = root.obj("review")
        val nodes = mapOf(Step.READING to reading.id(), Step.QUESTION to question.id(),
            Step.FEEDBACK to feedback.id(), Step.SOURCE to source.id(), Step.REVIEW to review.id())
        require(nodes.values.distinct().size == nodes.size) { "节点 ID 重复" }

        val options = question.get("choices")
        require(options != null && options.isJsonArray && options.asJsonArray.size() >= 2) { "选项不足或格式错误" }
        val choices = options.asJsonArray.map {
            require(it.isJsonObject) { "选项格式错误" }
            Choice(it.asJsonObject.id(), it.asJsonObject.text("text"))
        }
        require(choices.distinctBy { it.id }.size == choices.size) { "选项 ID 重复" }
        val correct = feedback.id("correctOptionId")
        require(choices.any { it.id == correct }) { "答案没有对应选项" }
        require(feedback.id("sourceId") == nodes[Step.SOURCE]) { "解释没有对应来源" }

        return Lesson(
            revision = root.id("packageVersion"), nodeIds = nodes, title = root.text("title"),
            introduction = root.text("introduction"), reading = reading.text("text"),
            question = question.text("prompt"), choices = choices, correctId = correct,
            explanation = feedback.text("explanation"),
            source = SourceExcerpt(source.text("title"), source.text("location"), source.text("text")),
            takeaway = review.text("text"),
        )
    }

    private fun JsonObject.obj(key: String): JsonObject {
        val value = get(key)
        require(value != null && value.isJsonObject) { "缺少对象 $key" }
        return value.asJsonObject
    }

    private fun JsonObject.text(key: String): String {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString &&
            value.asString.isNotBlank()) { "缺少有效文本 $key" }
        return value.asString
    }

    private fun JsonObject.id(key: String = "id") = text(key).also {
        require(idPattern.matches(it)) { "标识无效 $key" }
    }

    private companion object {
        val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
        val idPattern = Regex("[a-zA-Z0-9_-]+")
    }
}
