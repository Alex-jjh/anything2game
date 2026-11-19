package com.anything2game.app

import android.content.Context
import com.anything2game.app.data.content.AssetPackSource
import com.anything2game.app.data.content.ContentLibrary
import com.anything2game.app.data.content.DirectoryPackSource
import com.anything2game.app.data.AvatarPreferences
import com.anything2game.app.data.SharedPrefsAvatarPreferences
import com.anything2game.app.data.FramePreferences
import com.anything2game.app.data.SharedPrefsFramePreferences
import com.anything2game.app.data.FileMasteryStore
import com.anything2game.app.data.FileSessionStore
import com.anything2game.app.platform.ContextSource
import com.anything2game.app.platform.SimulatedContextSource
import com.anything2game.engine.context.ContextPolicy
import com.anything2game.engine.context.DefaultContextPolicy
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.grading.Grading
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.mastery.MasteryPolicy
import com.anything2game.engine.ports.Clock
import com.anything2game.engine.ports.FrameRepository
import com.anything2game.engine.ports.MasteryStore
import com.anything2game.engine.ports.PackageRepository
import com.anything2game.engine.ports.SessionStore
import java.io.File

/** Everything screens depend on. Screens see only interfaces, so tests and new adapters swap in freely. */
interface AppDependencies {
    val packages: PackageRepository
    val frames: FrameRepository
    val framePreferences: FramePreferences
    val homePreferences: com.anything2game.app.data.HomePreferences
    val avatarPreferences: AvatarPreferences
    val sessions: SessionStore
    val mastery: MasteryStore
    val grading: Grading
    val masteryPolicy: MasteryPolicy
    val contextSource: ContextSource
    val contextPolicy: ContextPolicy
    val clock: Clock
}

/** Composition root: the only place that picks concrete implementations. */
class AndroidAppContainer(context: Context) : AppDependencies {
    private val app = context.applicationContext
    // One content library over every pack source: bundled presets first, then packs installed on the device
    // (from our server or imported). Same file format everywhere; the engine sees only the two ports.
    val installedPacks = DirectoryPackSource(File(app.filesDir, "packs"))
    val content = ContentLibrary(listOf(AssetPackSource(app.assets), installedPacks))
    override val packages: PackageRepository = content
    override val frames: FrameRepository = content
    override val framePreferences: FramePreferences = SharedPrefsFramePreferences(app)
    override val homePreferences = com.anything2game.app.data.HomePreferences(app)
    override val avatarPreferences: AvatarPreferences = SharedPrefsAvatarPreferences(app)
    override val sessions: SessionStore = FileSessionStore(File(app.filesDir, "sessions"))
    override val mastery: MasteryStore = FileMasteryStore(File(app.filesDir, "mastery"))
    override val grading: Grading = GraderRegistry.default()
    override val masteryPolicy: MasteryPolicy = LeitnerPolicy()
    // Real sensors (Activity Recognition, ConnectivityManager) replace this adapter later; nothing else changes.
    override val contextSource: ContextSource = SimulatedContextSource()
    override val contextPolicy: ContextPolicy = DefaultContextPolicy
    override val clock: Clock = Clock.SYSTEM
}
