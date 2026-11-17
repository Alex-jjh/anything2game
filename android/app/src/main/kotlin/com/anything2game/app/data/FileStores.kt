package com.anything2game.app.data

import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.ports.MasteryStore
import com.anything2game.engine.ports.SessionStore
import com.anything2game.engine.session.SessionCodec
import com.anything2game.engine.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One small JSON file per package. Writes go to a temp file first so a crash never leaves half a record. */
private class JsonFiles(private val dir: File) {
    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    suspend fun read(key: String): String? = withContext(Dispatchers.IO) { file(key).takeIf { it.exists() }?.readText() }

    suspend fun write(key: String, text: String) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = file(key)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) { file(key).delete(); Unit }
}

class FileSessionStore(dir: File) : SessionStore {
    private val files = JsonFiles(dir)
    override suspend fun save(state: SessionState) = files.write(state.packageId, SessionCodec.encode(state))
    override suspend fun load(packageId: String): SessionState? = files.read(packageId)?.let(SessionCodec::decode)
    override suspend fun clear(packageId: String) = files.delete(packageId)
}

class FileMasteryStore(dir: File) : MasteryStore {
    private val files = JsonFiles(dir)
    override suspend fun all(packageId: String): Map<String, MasteryRecord> =
        files.read(packageId)?.let(SessionCodec::decodeMastery).orEmpty().associateBy { it.unitId }

    override suspend fun put(packageId: String, record: MasteryRecord) {
        val updated = all(packageId) + (record.unitId to record)
        files.write(packageId, SessionCodec.encodeMastery(updated.values.toList()))
    }
}
