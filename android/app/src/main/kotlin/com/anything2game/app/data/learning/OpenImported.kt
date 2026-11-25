package com.anything2game.app.data.learning

import com.anything2game.contract.space.SpaceMember

/** Where the reader goes once a file they brought has become a lesson: the Space that holds it, and the lesson itself. */
data class ImportedDestination(val spaceId: String, val sessionKey: String, val sourcePackage: String?, val version: String?)

/** Finds the lesson a finished import (or the sample) became, and the Space that has it. */
class OpenImported(private val materials: MaterialCatalog, private val spaces: SpaceRepository) {
    /**
     * [id] is the new lesson's key, or a course's package id. Null when no Space holds it yet; the reader then stays on the Spaces tab.
     */
    suspend operator fun invoke(id: String): ImportedDestination? {
        val source = materials.materials().firstOrNull { it.tutorKey == id || (it.member as? SpaceMember.LocalPackRef)?.packageId == id } ?: return null
        val parent = spaces.list().firstOrNull { source.member in it.members } ?: return null
        return ImportedDestination(parent.spaceId, source.tutorKey ?: id, source.localPack?.packageId, source.localPack?.contentVersion)
    }
}
