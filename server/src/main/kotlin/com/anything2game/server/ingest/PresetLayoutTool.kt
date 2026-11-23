package com.anything2game.server.ingest

import com.anything2game.contract.ContractJson
import com.anything2game.contract.DocumentLayout
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.PresetSourceLayout
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceLayout
import java.io.File
import java.security.MessageDigest

/**
 * Offline tooling (never run by the server): measures where a preset course's chunks sit on the pages of one local PDF
 * of the same document, so the pack can carry the geometry without the PDF (ADR-019). The upload path's own extractor,
 * chunker and aligner are reused, and the result is accepted only if that path reproduces the package's sources
 * exactly: same document version, same chunks. Then every chunk must be placed, or the tool fails and names it.
 */
object PresetLayoutTool {
    class Refused(message: String) : RuntimeException(message)

    /** Per chunk: how many of its non-whitespace characters got a box. Printed so a person sees how good the match is. */
    data class Report(val layout: PresetSourceLayout, val coverage: Map<String, Double>)

    /**
     * [allowUnaligned]: chunk ids a person has looked at and accepts with partial or no geometry (figure text, a rotated page); any
     * other chunk below [minCoverage] fails the run. [minCoverage] is of the chunk's characters, since a run breaks at
     * glyphs PDFBox gives no upright position (the arXiv side stamp) and those parts can never be marked.
     */
    fun measure(pkg: LearningPackage, pdf: ByteArray, pdfSource: String? = null, allowUnaligned: Set<String> = emptySet(), minCoverage: Double = 0.9): Report {
        if (!PdfTextExtractor.looksLikePdf(pdf)) throw Refused("The file is not a PDF (no %PDF- header).")
        val read = PdfTextExtractor(maxPages = 2_000, timeoutMillis = 300_000).extractWithGlyphs(pdf)
        val version = Chunker.documentVersion(read.pages)
        if (version != pkg.documentVersion) {
            throw Refused("This PDF is not the document ${pkg.packageId} was made from: its text has version $version, the package has ${pkg.documentVersion}. " +
                "Fetch the exact edition (eval corpus source) and check the PDFBox version has not changed.")
        }
        val chunks = Chunker.chunk(read.pages)
        if (chunks != pkg.sources) {
            val at = chunks.indices.firstOrNull { chunks[it] != pkg.sources.getOrNull(it) } ?: chunks.size
            throw Refused("The chunker no longer reproduces ${pkg.packageId}'s sources (${chunks.size} chunks vs ${pkg.sources.size}; first difference at " +
                "${pkg.sources.getOrNull(at)?.chunkId ?: chunks.getOrNull(at)?.chunkId}). The chunkIds would not match, so no layout is written.")
        }
        val unknown = allowUnaligned - chunks.map { it.chunkId }.toSet()
        if (unknown.isNotEmpty()) throw Refused("--allow-unaligned names chunks the package does not have: ${unknown.sorted()}")
        val measured = ChunkLayouts.build(chunks, read.glyphs)
        val built = measured.chunks.associateBy { it.chunkId }
        val coverage = chunks.associate { it.chunkId to coverageOf(it, built[it.chunkId]) }
        // Verified with the real chunk text, as the phone will use it: the whole chunk as a quote must give rectangles.
        val failing = chunks.filter { c -> (coverage.getValue(c.chunkId) < minCoverage || SourceLayout.rects(c, built[c.chunkId], c.text).isEmpty()) }.map { it.chunkId }
        val refused = failing - allowUnaligned
        if (refused.isNotEmpty()) {
            throw Refused("Chunks that could not be aligned (coverage below $minCoverage): " +
                refused.joinToString { "$it ${"%.2f".format(coverage.getValue(it))}" } + ". Check them on the page; if they cannot be marked (figures, rotated pages), list them in --allow-unaligned.")
        }
        // An accepted chunk keeps the part that did align: those boxes are as exact as any other, the rest gets no mark.
        val unaligned = failing.filter { it in allowUnaligned }
        val kept = chunks.mapNotNull { built[it.chunkId] }
        val layout = PresetSourceLayout(
            packageId = pkg.packageId, documentId = pkg.documentId, documentVersion = pkg.documentVersion,
            pdfSha256 = sha256(pdf), pdfBytes = pdf.size.toLong(), pageCount = read.pages.size, pdfSource = pdfSource,
            unaligned = unaligned, layout = DocumentLayout(measured.pages, kept),
        ).validate(pkg)
        return Report(layout, coverage)
    }

    /** Share of [chunk]'s non-whitespace characters inside one of its runs. */
    private fun coverageOf(chunk: SourceChunk, layout: com.anything2game.contract.ChunkLayout?): Double {
        val total = chunk.text.count { !SourceLayout.isGap(it) }
        if (layout == null || total == 0) return 0.0
        return layout.runs.sumOf { it.x.size / 2 }.toDouble() / total
    }

    fun encode(layout: PresetSourceLayout): String = ContractJson.json.encodeToString(PresetSourceLayout.serializer(), layout)

    /** `package.demo.attention.json` -> `layout.demo.attention.json`, next to it (where PackTool picks it up). */
    fun defaultOut(packageFile: File): File = File(packageFile.absoluteFile.parentFile, "layout." + packageFile.name.removePrefix("package."))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private const val USAGE = "usage: PresetLayoutTool --package <package.json> --pdf <local.pdf> [--source <url>] [--out <layout.json>] " +
    "[--allow-unaligned c40,c41] [--min-coverage 0.9]"

/** See server/README.md, "Preset source layouts". Writes only the layout file: never the PDF, never page images. */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { pair ->
        require(pair.size == 2 && pair[0].startsWith("--")) { USAGE }
        pair[0].removePrefix("--") to pair[1]
    }
    val unknownOpts = opts.keys - setOf("package", "pdf", "source", "out", "allow-unaligned", "min-coverage")
    require(unknownOpts.isEmpty()) { "unknown option(s) $unknownOpts; $USAGE" }
    val packageFile = File(opts["package"] ?: error(USAGE))
    val pdfFile = File(opts["pdf"] ?: error(USAGE))
    val pkg = ContractJson.readValidPackage(packageFile.readText()).getOrThrow()
    val out = opts["out"]?.let(::File) ?: PresetLayoutTool.defaultOut(packageFile)
    require(out.extension == "json") { "the output must be a .json file" }
    val report = try {
        PresetLayoutTool.measure(
            pkg, pdfFile.readBytes(), opts["source"],
            opts["allow-unaligned"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            opts["min-coverage"]?.toDouble() ?: 0.9,
        )
    } catch (e: PresetLayoutTool.Refused) {
        System.err.println("REFUSED: ${e.message}")
        kotlin.system.exitProcess(2)
    }
    out.writeText(PresetLayoutTool.encode(report.layout))
    val l = report.layout
    println("wrote ${out.path}: ${l.layout.chunks.size}/${pkg.sources.size} chunks on ${l.pageCount} pages, pdf sha256 ${l.pdfSha256} (${l.pdfBytes} bytes)")
    println("lowest coverage: " + report.coverage.entries.sortedBy { it.value }.take(5).joinToString { "${it.key} ${"%.3f".format(it.value)}" })
    if (l.unaligned.isNotEmpty()) println("accepted with partial or no geometry: ${l.unaligned}")
}
