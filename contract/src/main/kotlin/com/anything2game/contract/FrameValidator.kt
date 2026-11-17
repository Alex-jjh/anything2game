package com.anything2game.contract

/** Checks a frame overlay against its package and its [FrameSpec]. Same rules on server and client. */
object FrameValidator {

    fun validate(overlay: FrameOverlay, pkg: LearningPackage): List<Issue> {
        val issues = mutableListOf<Issue>()
        fun fail(path: String, message: String) { issues += Issue(path, message) }
        val spec = FrameSpec.of(overlay.frameId)
        val chunks = pkg.sources.associateBy { it.chunkId }

        if (overlay.schema != FRAME_SCHEMA_V1) fail("schema", "unsupported schema '${overlay.schema}'")
        if (overlay.packageId != pkg.packageId) fail("packageId", "overlay is for '${overlay.packageId}', not '${pkg.packageId}'")
        if (overlay.contentVersion != pkg.contentVersion) fail("contentVersion", "overlay is for version '${overlay.contentVersion}'")

        val castIds = overlay.cast.map { it.id }.toSet()
        val roles = overlay.cast.map { it.role }.toSet()
        (spec.requiredRoles - roles).forEach { fail("cast", "frame ${overlay.frameId} needs a ${it.name.lowercase()}") }

        fun lines(path: String, lines: List<Line>) = lines.forEachIndexed { i, l ->
            if (l.speaker !in castIds) fail("$path[$i]", "unknown speaker '${l.speaker}'")
            if (l.text.isBlank()) fail("$path[$i]", "empty line")
            PackageValidator.checkRefs("$path[$i]", l.sourceRefs, chunks, ::fail)
        }

        val levelIds = pkg.levels.map { it.levelId }.toSet()
        overlay.levels.filter { it.levelId !in levelIds }.forEach { fail("levels", "unknown level '${it.levelId}'") }
        if (spec.needsOverlay) {
            (levelIds - overlay.levels.map { it.levelId }.toSet()).forEach { fail("levels", "level '$it' has no overlay") }
        }

        overlay.levels.forEachIndexed { li, lo ->
            val lp = "levels[$li]"
            val level = pkg.level(lo.levelId) ?: return@forEachIndexed
            val unitIds = level.units.map { it.unitId }
            lines("$lp.intro", lo.intro)
            lines("$lp.outro", lo.outro)
            lo.leadIns.forEach { (unitId, l) ->
                if (unitId !in unitIds) fail("$lp.leadIns", "unknown unit '$unitId'")
                lines("$lp.leadIns[$unitId]", l)
            }
            if (spec.needsIntro && lo.intro.isEmpty()) fail("$lp.intro", "frame ${overlay.frameId} needs an intro")
            if (spec.needsOutro && lo.outro.isEmpty()) fail("$lp.outro", "frame ${overlay.frameId} needs an outro")
            if (spec.needsLeadIns) unitIds.filter { lo.leadIns[it].isNullOrEmpty() }.forEach { fail("$lp.leadIns", "unit '$it' has no lead-in") }

            val roomed = lo.rooms.flatMap { it.unitIds }
            if (spec.needsRooms) {
                if (lo.rooms.isEmpty()) fail("$lp.rooms", "frame ${overlay.frameId} needs rooms")
                (unitIds - roomed.toSet()).forEach { fail("$lp.rooms", "unit '$it' is in no room") }
            }
            roomed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { fail("$lp.rooms", "unit '$it' is in several rooms") }
            roomed.filter { it !in unitIds }.forEach { fail("$lp.rooms", "unknown unit '$it'") }

            val boss = lo.boss
            when {
                spec.needsBoss && boss == null -> fail("$lp.boss", "frame ${overlay.frameId} needs a boss")
                boss != null -> {
                    if (boss.mechanic !in spec.bossMechanics) fail("$lp.boss", "boss mechanic ${boss.mechanic} not allowed for ${overlay.frameId}")
                    if (boss.unitId in pkg.levels.flatMap { l -> l.units.map { it.unitId } }) fail("$lp.boss", "boss reuses unit id '${boss.unitId}'")
                    PackageValidator.checkUnit("$lp.boss", boss, chunks, ::fail)
                    val speaker = (boss.item as? CrossExamItem)?.speaker
                    if (speaker != null) {
                        val member = overlay.castMember(speaker)
                        if (member == null) fail("$lp.boss", "unknown speaker '$speaker'")
                        else if (spec.bossSpeakerRole != null && member.role != spec.bossSpeakerRole) fail("$lp.boss", "boss speaker must be the ${spec.bossSpeakerRole.name.lowercase()}")
                    }
                }
            }
        }

        val board = overlay.board
        if (spec.needsBoard && board == null) fail("board", "frame ${overlay.frameId} needs a board")
        if (board != null) {
            val allUnits = pkg.levels.flatMap { l -> l.units.map { it.unitId } }.toSet()
            val nodeIds = board.nodes.map { it.id }.toSet()
            board.nodes.filter { it.unitId !in allUnits }.forEach { fail("board", "node '${it.id}' points at unknown unit '${it.unitId}'") }
            if (spec.needsBoard) (allUnits - board.nodes.map { it.unitId }.toSet()).forEach { fail("board", "unit '$it' has no board node") }
            board.edges.filter { it.from !in nodeIds || it.to !in nodeIds }.forEach { fail("board", "edge ${it.from}→${it.to} points at unknown node") }
        }
        return issues
    }
}
