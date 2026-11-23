package com.anything2game.app.data.tutor

import com.anything2game.contract.tutor.TutorScript

/**
 * Pre-generated tutor content, one script per course that ships (or arrives) with one. The tutor repository and
 * the Learn tab ask this port; neither knows whether the script came from a bundled preset or an installed pack.
 */
interface TutorScripts {
    /** The script of the course the library currently serves, or null if that course has none. */
    suspend fun scriptFor(packageId: String): TutorScript?

    /** Resolve an old saved session against its exact installed script and package. */
    suspend fun savedScript(key: String): Pair<com.anything2game.contract.LearningPackage, TutorScript>? = null

    /** Ids of every course that has a script, in listing order. */
    suspend fun scriptedPackageIds(): List<String>
}
