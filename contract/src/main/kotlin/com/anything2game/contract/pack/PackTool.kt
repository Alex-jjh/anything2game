package com.anything2game.contract.pack

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.tutor.TutorScript
import java.io.File

/**
 * Turns loose JSON (as written by people and by the eval harness) into content packs.
 * Used by the app build to make the preset packs, and by anyone exporting server output by hand.
 */
object PackTool {
    /** Groups `package.*.json`, `frame.*.json` and `tutor.*.json` from [inputs] by packageId and writes one pack per package into [outDir]. */
    fun packDirectories(inputs: List<File>, outDir: File, origin: PackOrigin): List<File> {
        val files = inputs.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.extension == "json" } }.sortedBy { it.name }
        val packages = files.filter { it.name.startsWith("package.") }.map { ContractJson.decodePackage(it.readText()) }
        val frames = files.filter { it.name.startsWith("frame.") }.map { ContractJson.decodeFrame(it.readText()) }
        val tutors = files.filter { it.name.startsWith("tutor.") }.map { ContractJson.json.decodeFromString(TutorScript.serializer(), it.readText()) }
        val dupes = packages.groupBy { it.packageId }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "packageId appears in more than one file: $dupes" }
        val orphans = frames.map { it.packageId }.toSet() - packages.map { it.packageId }.toSet()
        require(orphans.isEmpty()) { "frames without a package: $orphans" }
        val tutorDupes = tutors.groupBy { it.packageId }.filterValues { it.size > 1 }.keys
        require(tutorDupes.isEmpty()) { "more than one tutor script for: $tutorDupes" }
        val tutorOrphans = tutors.map { it.packageId }.toSet() - packages.map { it.packageId }.toSet()
        require(tutorOrphans.isEmpty()) { "tutor scripts without a package: $tutorOrphans" }
        outDir.mkdirs()
        return packages.map { pkg -> writePack(pkg, frames.filter { it.packageId == pkg.packageId }, origin, outDir, tutors.firstOrNull { it.packageId == pkg.packageId }) }
    }

    fun writePack(pkg: LearningPackage, frames: List<FrameOverlay>, origin: PackOrigin, outDir: File, tutor: TutorScript? = null): File {
        val pack = PackCodec.build(pkg, frames, origin, tutor)
        val file = File(outDir, fileName(pkg.packageId))
        file.outputStream().use { PackCodec.write(pack, it) }
        return file
    }

    /** File name from the packageId, sanitised so an id can never act as a path. */
    fun fileName(packageId: String) = packageId.replace(Regex("[^A-Za-z0-9._-]"), "_") + PACK_EXTENSION
}

/** `PackTool <outDir> <inputDir>...` — writes preset packs. */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: PackTool <outDir> <inputDir>..." }
    val written = PackTool.packDirectories(args.drop(1).map(::File), File(args[0]), PackOrigin.PRESET)
    println("packed ${written.size} preset pack(s) into ${args[0]}")
}
