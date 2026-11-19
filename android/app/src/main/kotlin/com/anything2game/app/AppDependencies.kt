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
import com.anything2game.app.data.HomePreferences
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.data.profile.ProfileController
import com.anything2game.app.data.profile.ProfileKind
import com.anything2game.app.data.profile.ProfileRegistry
import com.anything2game.app.data.profile.SampleProfileSeeder
import com.anything2game.app.data.tutor.AndroidTutorRepository
import com.anything2game.app.data.tutor.TutorRepository
import com.anything2game.contract.pack.PackCodec
import com.anything2game.contract.pack.PackOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Everything screens depend on. Screens see only interfaces, so tests and new adapters swap in freely. */
interface AppDependencies {
    val tutors: com.anything2game.app.data.tutor.TutorRepository
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
    /** Who is playing (guest / sample student). Per-user stores above follow the active profile. */
    val profiles: ProfileController
}

/** Composition root: the only place that picks concrete implementations. */
class AndroidAppContainer(context: Context) : AppDependencies {
    private val app = context.applicationContext
    // One content library over every pack source: bundled presets first, then packs installed on the device
    // (from our server or imported). Same file format everywhere; the engine sees only the two ports.
    // Content is shared by all profiles; progress is not.
    val installedPacks = DirectoryPackSource(File(app.filesDir, "packs"))
    val content = ContentLibrary(listOf(AssetPackSource(app.assets), installedPacks))
    override val packages: PackageRepository = content
    override val frames: FrameRepository = content
    override val grading: Grading = GraderRegistry.default()
    override val masteryPolicy: MasteryPolicy = LeitnerPolicy()
    // Real sensors (Activity Recognition, ConnectivityManager) replace this adapter later; nothing else changes.
    override val contextSource: ContextSource = SimulatedContextSource()
    override val contextPolicy: ContextPolicy = DefaultContextPolicy
    override val clock: Clock = Clock.SYSTEM

    /** Everything that belongs to one person on this phone, built from the same store classes for every profile. */
    private inner class UserScope(val profile: LocalProfile) {
        private val dir = profile.dir(app)
        val sessions: SessionStore = FileSessionStore(File(dir, "sessions"))
        val mastery: MasteryStore = FileMasteryStore(File(dir, "mastery"))
        val framePreferences: FramePreferences = SharedPrefsFramePreferences(app, profile.prefs("frames"))
        val homePreferences = HomePreferences(app, profile.prefs("home"))
        val avatarPreferences: AvatarPreferences = SharedPrefsAvatarPreferences(app, profile.prefs("avatar"))
        val tutors = AndroidTutorRepository(app, packages, ::installServerPackage, File(dir, "tutor-sessions"), profile.prefs("tutor_connection"))
    }

    private suspend fun installServerPackage(pkg: com.anything2game.contract.LearningPackage) {
        val pack = PackCodec.build(pkg, emptyList(), PackOrigin.SERVER)
        installedPacks.install(PackCodec.toBytes(pack).inputStream()).getOrThrow()
        content.refresh()
    }

    private val registry = ProfileRegistry(app).also { r ->
        // Installs from before profiles existed keep playing as the guest instead of meeting the welcome screen.
        // Only once: the guest's own files live in the same place, so later "Switch profile → welcome" must stay on the welcome screen.
        val legacy = listOf("sessions", "mastery", "tutor-sessions").any { File(app.filesDir, it).list()?.isNotEmpty() == true }
        if (!r.hasChosen() && r.active() == null && legacy) r.setActive(LocalProfile.GUEST)
    }
    @Volatile private var scope = UserScope(registry.active() ?: LocalProfile.GUEST)

    override val tutors: TutorRepository get() = scope.tutors
    override val framePreferences: FramePreferences get() = scope.framePreferences
    override val homePreferences: HomePreferences get() = scope.homePreferences
    override val avatarPreferences: AvatarPreferences get() = scope.avatarPreferences
    override val sessions: SessionStore get() = scope.sessions
    override val mastery: MasteryStore get() = scope.mastery

    override val profiles: ProfileController = object : ProfileController {
        private val state = MutableStateFlow(registry.active())
        override val active: StateFlow<LocalProfile?> = state.asStateFlow()

        // A double tap on "Continue as Lin" must not play Lin's history twice (the seed is not idempotent: it adds to mastery).
        private val chooseLock = kotlinx.coroutines.sync.Mutex()

        override suspend fun choose(profile: LocalProfile) = chooseLock.withLock {
            val next = UserScope(profile)
            if (profile.kind == ProfileKind.SAMPLE && !registry.isSeeded(profile)) {
                SampleProfileSeeder(packages, frames, grading, masteryPolicy, next.sessions, next.mastery, next.homePreferences, next.avatarPreferences, next.framePreferences, clock)
                    .seed(nickname = app.getString(R.string.sample_profile_name), avatarId = "pip")
                registry.markSeeded(profile)
            }
            scope = next
            registry.setActive(profile)
            state.value = profile
        }

        override fun leave() {
            registry.setActive(null)
            state.value = null
        }
    }
}
