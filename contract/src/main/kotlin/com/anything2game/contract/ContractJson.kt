package com.anything2game.contract

import kotlinx.serialization.json.Json

/** The one JSON configuration used by client, server and tests. */
object ContractJson {
    val json: Json = Json {
        classDiscriminator = "type"
        // Additive changes (new optional fields) must not break older readers.
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    /** Executable activity configurations have a closed vocabulary. A new rule requires a supported schema. */
    val activityJson = Json(json) { ignoreUnknownKeys = false }

    fun decodePackage(text: String): LearningPackage = json.decodeFromString(LearningPackage.serializer(), text)

    fun encodePackage(pkg: LearningPackage): String = json.encodeToString(LearningPackage.serializer(), pkg)

    fun decodeFrame(text: String): FrameOverlay = json.decodeFromString(FrameOverlay.serializer(), text)
    fun encodeFrame(overlay: FrameOverlay): String = json.encodeToString(FrameOverlay.serializer(), overlay)

    /** Parse and validate a frame overlay against its package. */
    fun readValidFrame(text: String, pkg: LearningPackage): Result<FrameOverlay> = runCatching {
        val overlay = decodeFrame(text)
        val issues = FrameValidator.validate(overlay, pkg)
        require(issues.isEmpty()) { "Invalid frame: " + issues.joinToString("; ") }
        overlay
    }

    /** Parse and validate in one step. Returns the package only when it has no issues. */
    fun readValidPackage(text: String): Result<LearningPackage> = runCatching {
        val pkg = decodePackage(text)
        val issues = PackageValidator.validate(pkg)
        require(issues.isEmpty()) { "Invalid package: " + issues.joinToString("; ") }
        pkg
    }
}
