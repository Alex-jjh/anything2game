package com.anything2game.contract.pack

import com.anything2game.contract.game.GameSpec
import com.anything2game.contract.space.LearningPath

const val PACK_SCHEMA_V2 = "a2g.pack/v2"

sealed interface ActivityPayload {
    data class Game(val game: GameSpec) : ActivityPayload
    data class Path(val path: LearningPath) : ActivityPayload
}

data class ActivityPack(val manifest: PackManifest, val payload: ActivityPayload)
