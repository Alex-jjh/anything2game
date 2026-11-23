package com.anything2game.app.data

import com.anything2game.contract.ContractJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal fun stableId(vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach {
        val bytes = it.toByteArray()
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal val fileLocks = ConcurrentHashMap<String, Mutex>()
internal fun File.mutex() = fileLocks.computeIfAbsent(canonicalPath) { Mutex() }

internal fun File.promote(target: File) {
    Files.move(toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
}

/** Lock by canonical filename so a reopened profile and an in-flight callback serialize on the same file. */
internal class AtomicSnapshot<T>(private val file: File, private val serializer: KSerializer<T>, private val empty: () -> T) {
    private val lock = file.mutex()
    private fun load(): T = if (file.exists()) ContractJson.json.decodeFromString(serializer, file.readText()) else empty()

    suspend fun read(): T = withContext(Dispatchers.IO) { lock.withLock { load() } }
    suspend fun update(edit: (T) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val prior = load()
            val next = edit(prior)
            if (prior != next) {
                val dir = requireNotNull(file.parentFile)
                check(dir.isDirectory || dir.mkdirs())
                val tmp = File.createTempFile("state", ".part", dir)
                try {
                    tmp.outputStream().use {
                        it.write(ContractJson.json.encodeToString(serializer, next).toByteArray())
                        it.fd.sync()
                    }
                    tmp.promote(file)
                } finally { tmp.delete() }
            }
            next
        }
    }
}
