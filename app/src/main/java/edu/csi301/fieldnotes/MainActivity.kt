package edu.csi301.fieldnotes

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import android.window.OnBackInvokedDispatcher
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val repository: LessonRepository = JsonLessonRepository { assets.open(SAMPLE_ASSET).bufferedReader() }
    private lateinit var lesson: Lesson
    private var bookmark = Bookmark()
    private val storage by lazy { ProgressStore(this) }
    private var progress: Progress? = null
    private var inLesson = false
    private lateinit var page: LinearLayout
    private val forest = Color.rgb(36, 82, 63)
    private val ink = Color.rgb(32, 49, 41)
    private val muted = Color.rgb(76, 94, 83)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository.sample().onSuccess {
            lesson = it
            bookmark = storage.load(it)
            progress = bookmark.progress
        }
        inLesson = progress != null && savedInstanceState?.getBoolean("inLesson") == true
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::back
            )
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("inLesson", inLesson)
    }

    @Deprecated("Used on older Android versions")
    override fun onBackPressed() = back()

    private fun back() {
        val current = progress
        when {
            !inLesson -> finish()
            current?.step == Step.SOURCE -> advance(current.copy(step = Step.FEEDBACK))
            else -> showLibrary()
        }
    }

    private fun showLibrary() {
        inLesson = false
        render()
    }

    private fun advance(next: Progress) {
        if (!storage.save(lesson, next)) {
            Toast.makeText(this, "进度保存失败，请检查设备存储后重试。", Toast.LENGTH_LONG).show()
            return
        }
        progress = next
        bookmark = Bookmark(next)
        inLesson = true
        render()
    }

    private fun render() {
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
            setBackgroundColor(Color.rgb(247, 246, 240))
        }
        val frame = FrameLayout(this)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(26))
        }
        val width = minOf(resources.displayMetrics.widthPixels, dp(620))
        frame.addView(page, FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        scroll.addView(frame)
        setContentView(scroll)

        label(page, getString(R.string.sample_notice), 14, forest, true)
        if (!::lesson.isInitialized) {
            heading("示例暂不可用")
            label(card(), "无法读取示例资料。已有学习记录已保留。", 18)
            pdfAction()
            action("退出") { finish() }
            return
        }
        val current = progress
        if (!inLesson || current == null) {
            library()
            pdfAction()
            return
        }
        when (current.step) {
            Step.READING -> {
                heading(lesson.title)
                label(card(), lesson.reading, 19)
                action("回答问题") { advance(current.toQuestion()) }
            }
            Step.QUESTION -> {
                heading("想一想")
                label(card(), lesson.question, 20, ink, true)
                lesson.choices.forEach { option ->
                    action(option.text, primary = false) { advance(current.answer(option.id, lesson)) }
                }
            }
            Step.FEEDBACK -> {
                val correct = current.answerId == lesson.correctId
                heading(if (correct) "答对了" else "再看一眼记录")
                val panel = card()
                label(panel, "你的选择", 14, muted, true)
                label(panel, choiceText(current.answerId), 18)
                if (!correct) {
                    label(panel, "正确答案", 14, forest, true)
                    label(panel, choiceText(lesson.correctId), 18)
                }
                label(panel, "解释", 14, forest, true)
                label(panel, lesson.explanation, 18)
                action("查看对应原文") { advance(current.openSource()) }
            }
            Step.SOURCE -> {
                heading("来源片段")
                val panel = card()
                label(panel, "《" + lesson.source.title + "》 · " + lesson.source.location, 19, ink, true)
                label(panel, "项目自拟示例原文 · 虚构观察", 14, muted)
                label(panel, lesson.source.text, 19)
                action("完成并查看回顾") { advance(current.finish()) }
            }
            Step.REVIEW -> {
                heading("学习回顾")
                val panel = card()
                label(panel, lesson.title, 21, ink, true)
                label(panel, lesson.takeaway, 18)
                label(panel, if (current.answerId == lesson.correctId) "本轮答题 · 正确" else "本轮答题 · 已查看纠正", 15, forest, true)
                label(panel, "你的选择：" + choiceText(current.answerId), 17)
            }
        }
        action("返回资料库", primary = false, run = ::showLibrary)
    }

    private fun library() {
        heading("示例资料库")
        val panel = card()
        label(panel, lesson.source.title, 15, forest, true)
        label(panel, lesson.title, 25, ink, true)
        label(panel, lesson.introduction, 17)
        bookmark.message?.let { label(panel, it, 16, muted) }
        if (bookmark.blocked) return
        val step = progress?.step
        val bookmark = when (step) {
            null -> "尚未开始"
            Step.READING -> "上次停在学习内容"
            Step.QUESTION -> "有一道题待回答"
            Step.FEEDBACK -> "已保留本轮答案"
            Step.SOURCE -> "上次停在来源片段"
            Step.REVIEW -> "本轮已完成"
        }
        label(panel, bookmark, 15, muted)
        val openLabel = when (step) {
            null -> "开始学习"
            Step.REVIEW -> "查看回顾"
            else -> "继续学习"
        }
        action(openLabel) {
            if (progress == null) advance(Progress()) else {
                inLesson = true
                render()
            }
        }
        if (step == Step.REVIEW) action("重新学习", primary = false) { advance(Progress()) }
    }

    private fun choiceText(id: String?) = lesson.choices.first { it.id == id }.text

    private fun pdfAction() = action("选择 PDF（本地模拟）", primary = false) {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
        try {
            startActivityForResult(picker, PICK_PDF)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "设备上没有可用的文件选择器。", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Platform file picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PDF || resultCode != RESULT_OK) return
        val uri = data?.data
        if (uri?.scheme != "content") {
            Toast.makeText(this, "无法读取所选文件的信息，请重新选择。", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, PdfMockActivity::class.java).setData(uri))
    }

    private companion object {
        const val PICK_PDF = 41
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()

    private fun heading(text: String) = label(page, text, 32, ink, true)

    private fun label(parent: LinearLayout, value: String, size: Int, color: Int = ink, bold: Boolean = false) {
        parent.addView(TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(6))
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(1), Color.rgb(211, 218, 208))
        }
        page.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(0, dp(12), 0, dp(12))
        })
    }

    private fun action(title: String, primary: Boolean = true, run: () -> Unit) {
        page.addView(Button(this).apply {
            text = title
            textSize = 17f
            isAllCaps = false
            minHeight = dp(52)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            backgroundTintList = ColorStateList.valueOf(if (primary) forest else Color.rgb(227, 233, 224))
            setTextColor(if (primary) Color.WHITE else ink)
            setOnClickListener { run() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}
