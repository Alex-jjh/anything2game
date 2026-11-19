package com.anything2game.server.tutor

import com.anything2game.contract.tutor.*
import kotlinx.serialization.Serializable

@Serializable data class TutorRecord(
    val ownerId: String, val blueprint: TutorBlueprint, val session: TutorSession,
    val receipts: Map<String, String> = emptyMap(), val cancelled: Set<String> = emptySet(),
)

interface TutorStore {
    /** Returns the existing record on duplicate ID; null if the document has been deleted. */
    fun createTutor(record: TutorRecord): TutorRecord?
    fun getTutor(id: String): TutorRecord?
    /** Compare-and-set prevents stale model results from replacing cancellation or another turn. */
    fun saveTutorIfRevision(record: TutorRecord, expectedRevision: Int): Boolean
}
