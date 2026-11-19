package com.anything2game.server.export

import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameSpec
import com.anything2game.contract.pack.ContentPack
import com.anything2game.contract.pack.PackCodec
import com.anything2game.contract.pack.PackOrigin
import com.anything2game.server.store.PackageRepository

/**
 * Server output → a content pack, in exactly the format the app bundles its presets in (ADR-017).
 * Not exposed over HTTP yet: a download endpoint is a separate, security-reviewed step (ownership check,
 * auth, size limits). Until then this is used by tests and by local export.
 */
class PackExporter(private val packages: PackageRepository) {
    /** Null when the package is unknown or still compiling (frames are only made for finished packages). */
    fun export(packageId: String): ContentPack? {
        val stored = packages.getPackage(packageId)?.takeIf { it.complete } ?: return null
        val frames = FrameId.entries.filter { FrameSpec.of(it).needsOverlay }.mapNotNull { packages.getFrame(packageId, it) }
        return PackCodec.build(stored.pkg, frames, PackOrigin.SERVER)
    }
}
