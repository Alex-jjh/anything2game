package edu.csi301.fieldnotes

import android.content.Context

class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("learning", Context.MODE_PRIVATE)

    fun load(lesson: Lesson) = readBookmark(prefs.all, lesson)

    // A single tiny checkpoint. Confirm it is on disk before advancing the screen.
    fun save(lesson: Lesson, progress: Progress): Boolean =
        prefs.edit().putString(lesson.revision, progress.encode(lesson)).commit()
}

data class Bookmark(val progress: Progress? = null, val message: String? = null, val blocked: Boolean = false)

// Preferences in this file belong exclusively to versioned sample checkpoints.
fun readBookmark(records: Map<String, *>, lesson: Lesson): Bookmark {
    if (!records.containsKey(lesson.revision)) {
        return if (records.isEmpty()) Bookmark() else Bookmark(message = "其他版本的学习记录已保留，本版本可单独开始。")
    }
    val progress = Progress.decode(records[lesson.revision] as? String, lesson)
    return if (progress != null) Bookmark(progress)
        else Bookmark(message = "当前记录与示例不匹配或已损坏。原记录已保留。", blocked = true)
}
