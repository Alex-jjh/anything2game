package com.anything2game.engine.session

import com.anything2game.contract.ContractJson
import com.anything2game.engine.mastery.MasteryRecord
import kotlinx.serialization.builtins.ListSerializer

/** JSON for persisted engine state, shared by every SessionStore/MasteryStore implementation. */
object SessionCodec {
    private val json = ContractJson.json

    fun encode(state: SessionState): String = json.encodeToString(SessionState.serializer(), state)
    fun decode(text: String): SessionState? = runCatching { json.decodeFromString(SessionState.serializer(), text) }.getOrNull()

    fun encodeMastery(records: List<MasteryRecord>): String = json.encodeToString(ListSerializer(MasteryRecord.serializer()), records)
    fun decodeMastery(text: String): List<MasteryRecord> =
        runCatching { json.decodeFromString(ListSerializer(MasteryRecord.serializer()), text) }.getOrDefault(emptyList())
}
