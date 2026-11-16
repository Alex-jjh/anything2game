package edu.csi301.fieldnotes

import android.app.Activity
import android.content.ContentResolver
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private data class FileInfo(val name: String, val bytes: Long?)

private fun fileInfo(resolver: ContentResolver, uri: Uri): FileInfo? {
    val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    return resolver.query(uri, columns, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val size = cursor.getColumnIndex(OpenableColumns.SIZE)
        FileInfo(
            if (name >= 0) cursor.getString(name)?.takeIf { it.isNotBlank() } ?: "名称未知" else "名称未知",
            if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size).takeIf { it >= 0 } else null,
        )
    }
}

// A rotation keeps the same timer and request IDs. No Activity is retained here.
private class MockTimers(initial: MockStage) {
    private val handler = Handler(Looper.getMainLooper())
    val run = PdfMockRun(initial) { delay, callback -> handler.postDelayed({ callback() }, delay) }
    fun close() = handler.removeCallbacksAndMessages(null)
}

class PdfMockActivity : Activity() {
    private lateinit var timers: MockTimers
    private val run get() = timers.run
    private var file: FileInfo? = null
    private var metadataFailed = false
    private var interrupted = false
    private lateinit var page: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = savedInstanceState?.getString("stage")?.let { MockStage.valueOf(it) }
        val retained = lastNonConfigurationInstance as? MockTimers
        interrupted = savedInstanceState?.getBoolean("interrupted") == true ||
            (retained == null && previous == MockStage.RUNNING)
        timers = retained ?: MockTimers(previous?.takeUnless { it == MockStage.RUNNING } ?: MockStage.CONFIRM)
        run.onChange = ::render
        savedInstanceState?.getString("name")?.let {
            file = FileInfo(it, savedInstanceState.getLong("bytes", -1).takeIf { size -> size >= 0 })
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::back)
        }
        render()
        if (file == null) fetchMetadata()
    }

    private fun fetchMetadata() {
        val uri = intent.data
        thread(name = "pdf-display-info") {
            val info = try {
                uri?.let { fileInfo(contentResolver, it) }
            } catch (_: RuntimeException) {
                // A document provider can disappear or revoke access before this query.
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                file = info
                metadataFailed = info == null
                render()
            }
        }
    }

    override fun onRetainNonConfigurationInstance(): Any = timers

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("stage", run.stage.name)
        outState.putBoolean("interrupted", interrupted)
        file?.let {
            outState.putString("name", it.name)
            outState.putLong("bytes", it.bytes ?: -1)
        }
    }

    override fun onDestroy() {
        run.onChange = null
        if (!isChangingConfigurations) timers.close()
        super.onDestroy()
    }

    @Deprecated("Used on older Android versions")
    override fun onBackPressed() = back()

    private fun back() {
        if (run.stage == MockStage.RUNNING) run.cancel() else finish()
    }

    private fun submit(outcome: MockOutcome) {
        interrupted = false
        run.start(outcome)
    }

    private fun render() {
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (20 * resources.displayMetrics.density).roundToInt()
            setPadding(margin, margin, margin, margin)
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(page) })
        label("本地 mock · 未上传、未解析 PDF · 未调用 AI", 16)
        val info = file
        if (info == null) {
            label(if (metadataFailed) "无法读取所选文件的信息，请返回重新选择。" else "正在读取文件信息…", 20)
            button("返回资料库", ::finish)
            return
        }
        val heading = when (run.stage) {
            MockStage.CONFIRM, MockStage.CANCELLED -> "确认文件"
            MockStage.RUNNING -> "正在模拟生成"
            MockStage.READY -> "模拟生成成功"
            MockStage.FAILED -> "模拟生成失败"
        }
        label(heading, 28)
        label(info.name, 20)
        label(info.bytes?.let { "文件大小：$it 字节" } ?: "文件大小未知", 16)
        when (run.stage) {
            MockStage.CONFIRM, MockStage.CANCELLED -> {
                if (interrupted) label("上次模拟已中断，可重新开始。", 16)
                if (run.stage == MockStage.CANCELLED) label("已取消模拟，可重新开始。", 16)
                button("开始模拟生成") { submit(MockOutcome.SUCCESS) }
                button("演示失败后重试") { submit(MockOutcome.FAILURE) }
                button("演示迟到响应（12 秒）") { submit(MockOutcome.LATE_SUCCESS) }
            }
            MockStage.RUNNING -> {
                label("等待本地模拟响应。可以取消并重新开始。", 17)
                button("取消模拟") { run.cancel() }
            }
            MockStage.FAILED -> {
                label("本次为预设失败。重试将使用同一文件演示成功。", 17)
                button("重试模拟生成") { submit(MockOutcome.SUCCESS) }
            }
            MockStage.READY -> label("本次只完成了模拟任务，未生成学习包。", 17)
        }
        if (run.ignoredLateResult) label("已忽略旧任务的迟到响应。", 16)
        if (run.stage != MockStage.RUNNING) button("返回资料库", ::finish)
    }

    private fun label(value: String, size: Int) {
        page.addView(TextView(this).apply {
            text = value
            textSize = size.toFloat()
            if (size >= 20) setTypeface(typeface, Typeface.BOLD)
            val gap = (4 * resources.displayMetrics.density).roundToInt()
            setPadding(0, gap, 0, gap * 2)
        })
    }

    private fun button(title: String, click: () -> Unit) {
        page.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener { click() }
        })
    }
}
