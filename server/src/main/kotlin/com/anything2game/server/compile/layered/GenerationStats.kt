package com.anything2game.server.compile.layered

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Counters of one compile job for the A/B experiment. Logged as ONE line, `layered stats {json}` (layered) or `classic stats {json}`
 * (classic with the support gate), when the job's generation ends; the eval reads the server log. Contains counts and ids only, never
 * document text. Thread-safe: units are generated in parallel.
 */
class GenerationStats(val jobId: String, val pipeline: String, val gate: Boolean, private val clock: () -> Long = System::currentTimeMillis) {
    private val startedAt = clock()

    // L1 claims
    val claimsExtracted = AtomicInteger()
    val claimBatches = AtomicInteger()
    val claimBatchesFailed = AtomicInteger()
    val claimsDroppedQuote = AtomicInteger()
    val claimsDroppedNumber = AtomicInteger()
    val claimsDroppedScope = AtomicInteger()
    val claimsDroppedEntailment = AtomicInteger()
    val conflictPairs = AtomicInteger()
    val conflictClaims = AtomicInteger()
    val claimsKept = AtomicInteger()
    val conflictPairIds = java.util.concurrent.CopyOnWriteArrayList<String>()

    // L2 plan
    val unitsPlanned = AtomicInteger()
    val orderSwitched = AtomicInteger()

    // L3 units
    val unitsGenerated = AtomicInteger()
    val unitsKept = AtomicInteger()
    val unitsRepaired = AtomicInteger()
    val unitsDropped = AtomicInteger()

    // support gate (both pipelines): part-level verdicts and unit-level outcomes
    val gateUnitsChecked = AtomicInteger()
    val gatePartsCorrect = AtomicInteger()
    val gatePartsWrong = AtomicInteger()
    val gatePartsAmbiguous = AtomicInteger()
    val gateUnitsRejected = AtomicInteger()
    val gateRegenerations = AtomicInteger()
    val gateUnitsDropped = AtomicInteger()

    private val levelMillis = ConcurrentHashMap<String, Long>()
    val claimsMillis = AtomicLong()
    val planMillis = AtomicLong()

    /** Time from the start of the job to the moment level [levelId] was published (or failed). */
    fun levelDone(levelId: String) { levelMillis[levelId] = clock() - startedAt }

    fun toJson(): JsonObject = buildJsonObject {
        put("jobId", jobId)
        put("pipeline", pipeline)
        put("gate", gate)
        put("claims", buildJsonObject {
            put("batches", claimBatches.get()); put("batchesFailed", claimBatchesFailed.get())
            put("extracted", claimsExtracted.get())
            put("droppedQuote", claimsDroppedQuote.get()); put("droppedNumber", claimsDroppedNumber.get())
            put("droppedScope", claimsDroppedScope.get()); put("droppedEntailment", claimsDroppedEntailment.get())
            put("conflictPairs", conflictPairs.get()); put("conflictClaims", conflictClaims.get())
            put("conflictPairIds", JsonArray(conflictPairIds.map { JsonPrimitive(it) }))
            put("kept", claimsKept.get())
        })
        put("plan", buildJsonObject { put("units", unitsPlanned.get()); put("orderSwitched", orderSwitched.get()) })
        put("units", buildJsonObject {
            put("generated", unitsGenerated.get()); put("repaired", unitsRepaired.get()); put("dropped", unitsDropped.get()); put("kept", unitsKept.get())
        })
        put("gate", buildJsonObject {
            put("unitsChecked", gateUnitsChecked.get())
            put("partsCorrect", gatePartsCorrect.get()); put("partsWrong", gatePartsWrong.get()); put("partsAmbiguous", gatePartsAmbiguous.get())
            put("unitsRejected", gateUnitsRejected.get()); put("regenerations", gateRegenerations.get()); put("unitsDropped", gateUnitsDropped.get())
        })
        put("millis", buildJsonObject {
            put("claims", claimsMillis.get()); put("plan", planMillis.get())
            put("levels", buildJsonObject { levelMillis.toSortedMap().forEach { (k, v) -> put(k, v) } })
            put("total", clock() - startedAt)
        })
    }

    /** The log line. [jobId] is already inside the JSON. */
    fun logLine(): String = "${if (pipeline == "layered") "layered" else "classic"} stats ${toJson()}"
}
