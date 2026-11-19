package com.anything2game.engine.frames

import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.FrameSpec
import com.anything2game.contract.Level
import com.anything2game.contract.LearningUnit
import com.anything2game.contract.LevelOverlay
import com.anything2game.contract.Room
import com.anything2game.engine.session.Slot
import com.anything2game.engine.session.SlotKind

/**
 * A frame turns a level (+ its overlay) into the ordered list of slots the player goes through.
 * Frames never grade and never touch item content (docs/product/game-concepts.md §7).
 */
interface GameFrame {
    val id: FrameId
    val spec: FrameSpec
    fun plan(level: Level, overlay: LevelOverlay?): List<Slot>

    /** A frame is playable for a package only when its overlay (if needed) exists. */
    fun isPlayable(overlay: FrameOverlay?): Boolean = !spec.needsOverlay || overlay != null
}

/**
 * The shared script all five current frames follow; the overlay's data makes them differ:
 * intro → (room scene) → (lead-in scene) → unit … → boss → outro.
 * A frame with a different shape (e.g. a roguelite map) implements [GameFrame] itself.
 *
 * Two frames add challenges of their own *between* the clues, derived from the level's items ([DerivedChallenges]):
 * Rookie follows up to two units with a teach-back, Escape ends each room with a lock.
 */
class ScriptedFrame(override val spec: FrameSpec) : GameFrame {
    override val id: FrameId get() = spec.frameId

    override fun plan(level: Level, overlay: LevelOverlay?): List<Slot> {
        if (overlay == null) return level.units.map { Slot(it.unitId) }
        val slots = mutableListOf<Slot>()
        val teachBacks = if (id == FrameId.ROOKIE) DerivedChallenges.teachBackUnitIds(level).toSet() else emptySet()
        fun scene(key: String) { slots += Slot(unitId = "", kind = SlotKind.SCENE, scene = key) }
        fun unit(unitId: String) {
            if (!overlay.leadIns[unitId].isNullOrEmpty()) scene(Slot.leadIn(unitId))
            slots += Slot(unitId)
            if (unitId in teachBacks) slots += Slot(unitId, kind = SlotKind.TEACH_BACK)
        }
        if (overlay.intro.isNotEmpty()) scene(Slot.INTRO)
        if (overlay.rooms.isNotEmpty()) {
            val inRooms = overlay.rooms.flatMap { it.unitIds }.toSet()
            overlay.rooms.forEach { room ->
                scene(Slot.room(room.id))
                room.unitIds.forEach(::unit)
                if (id == FrameId.ESCAPE && DerivedChallenges.roomCheck(roomUnits(level, room), language = "en") != null) {
                    slots += Slot(unitId = "", kind = SlotKind.ROOM_CHECK, roomId = room.id)
                }
            }
            level.units.map { it.unitId }.filter { it !in inRooms }.forEach(::unit)
        } else {
            level.units.forEach { unit(it.unitId) }
        }
        overlay.boss?.let { slots += Slot(it.unitId, kind = SlotKind.BOSS) }
        if (overlay.outro.isNotEmpty()) scene(Slot.OUTRO)
        return slots
    }
}

/** The level's units of [room], in the room's order. */
internal fun roomUnits(level: Level, room: Room): List<LearningUnit> =
    room.unitIds.mapNotNull { id -> level.units.firstOrNull { it.unitId == id } }

object Frames {
    private val byId: Map<FrameId, GameFrame> = FrameSpec.ALL.mapValues { (_, spec) -> ScriptedFrame(spec) }
    val CLASSIC: GameFrame = of(FrameId.CLASSIC)
    fun of(id: FrameId): GameFrame = byId.getValue(id)
    val all: List<GameFrame> get() = FrameId.entries.map(::of)
}
