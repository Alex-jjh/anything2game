package com.anything2game.app.data

import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.ports.MasteryStore
import com.anything2game.engine.ports.SessionStore
import com.anything2game.engine.session.SessionCodec
import com.anything2game.engine.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Existing installs used this lossy filename transform. New writes always use opaque, framed hashes. */
private fun File.legacyFile(key: String) = File(this, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

@Serializable
private data class Sessions(val latest: String? = null, val versions: Map<String, SessionState> = emptyMap(), val cleared: Boolean = false)

class FileSessionStore(private val dir: File) : SessionStore {
    private fun store(id: String) = AtomicSnapshot(File(dir, "versioned/${stableId(id)}.json"), Sessions.serializer(), ::Sessions)
    private suspend fun legacy(id: String) = withContext(Dispatchers.IO) {
        dir.legacyFile(id).takeIf(File::isFile)?.readText()?.let(SessionCodec::decode)?.takeIf { it.packageId == id }
    }
    override suspend fun save(state: SessionState) {
        store(state.packageId).update { it.copy(latest = state.contentVersion, versions = it.versions + (state.contentVersion to state)) }
    }
    override suspend fun load(packageId: String): SessionState? {
        val snapshot = store(packageId).read()
        return snapshot.versions[snapshot.latest] ?: if (snapshot.cleared) null else legacy(packageId)
    }
    override suspend fun load(packageId: String, version: String): SessionState? {
        val snapshot = store(packageId).read()
        return snapshot.versions[version] ?: if (snapshot.cleared) null else legacy(packageId)?.takeIf { it.contentVersion == version }
    }
    override suspend fun clear(packageId: String) { store(packageId).update { Sessions(cleared = true) } }
}

class FileMasteryStore(private val dir: File, private val currentVersion: suspend (String) -> String? = { null }) : MasteryStore {
    private fun store(id: String, version: String) = AtomicSnapshot(File(dir, "versioned/${stableId(id, version)}.json"),
        ListSerializer(MasteryRecord.serializer()), ::emptyList)
    override suspend fun legacyUnversioned(packageId: String): Map<String, MasteryRecord> = withContext(Dispatchers.IO) {
        dir.legacyFile(packageId).takeIf(File::isFile)?.readText()?.let(SessionCodec::decodeMastery).orEmpty().associateBy { it.unitId }
    }
    override suspend fun all(packageId: String, version: String) = store(packageId, version).read().associateBy { it.unitId }
    override suspend fun put(packageId: String, version: String, record: MasteryRecord) {
        store(packageId, version).update { it.filterNot { old -> old.unitId == record.unitId } + record }
    }
    override suspend fun all(packageId: String) = currentVersion(packageId)?.let { all(packageId, it) }.orEmpty()
    override suspend fun put(packageId: String, record: MasteryRecord) =
        put(packageId, requireNotNull(currentVersion(packageId)) { "A known content version is required" }, record)
}
