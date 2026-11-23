package com.anything2game.app.data.content

import com.anything2game.app.data.AtomicSnapshot
import com.anything2game.app.data.stableId
import com.anything2game.contract.ContractJson
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.pack.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import java.io.File

@Serializable
private data class CourseReceipt(val owner: String, val documentId: String, val documentVersion: String, val manifestHash: String)

/** Only the authenticated package-response path writes receipts. Shared installed archives cannot self-assert ownership. */
internal class AuthenticatedCourses(file: File, private val content: ContentLibrary, private val owner: () -> String?) {
    private val receipts = AtomicSnapshot(file, SetSerializer(CourseReceipt.serializer()), ::emptySet)
    private fun receipt(owner: String, manifest: PackManifest, pkg: LearningPackage) =
        CourseReceipt(owner, pkg.documentId, pkg.documentVersion, stableId(ContractJson.json.encodeToString(PackManifest.serializer(), manifest)))

    suspend fun install(pkg: LearningPackage) {
        val identity = requireNotNull(owner()) { "No authenticated package owner" }
        val pack = PackCodec.build(pkg, emptyList(), PackOrigin.SERVER)
        val installed = content.install(PackCodec.toBytes(pack).inputStream())
        receipts.update { it + receipt(identity, installed, pkg) }
    }

    suspend fun owns(pack: ContentPack): Boolean {
        val identity = owner() ?: return false
        return pack.manifest.origin == PackOrigin.SERVER && receipt(identity, pack.manifest, pack.pkg) in receipts.read()
    }
}
