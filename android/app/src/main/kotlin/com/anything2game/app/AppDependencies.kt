package com.anything2game.app

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import com.anything2game.app.data.api.ApiTransport
import com.anything2game.app.data.api.ActivityApi
import com.anything2game.app.data.learning.*
import com.anything2game.app.data.tutor.TutorHttpClient
import com.anything2game.app.data.content.AuthenticatedCourses
import com.anything2game.app.data.content.AssetPackSource
import com.anything2game.app.data.content.ContentLibrary
import com.anything2game.app.data.content.DirectoryPackSource
import com.anything2game.app.data.AvatarPreferences
import com.anything2game.app.data.SharedPrefsAvatarPreferences
import com.anything2game.app.data.ExplainerPreferences
import com.anything2game.app.data.SharedPrefsExplainerPreferences
import com.anything2game.app.data.FramePreferences
import com.anything2game.app.data.SharedPrefsFramePreferences
import com.anything2game.app.data.FileMasteryStore
import com.anything2game.app.data.FileSessionStore
import com.anything2game.app.platform.ContextSource
import com.anything2game.app.platform.SimulatedContextSource
import com.anything2game.engine.context.ContextPolicy
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
import com.anything2game.app.data.source.SourceLibrary
import com.anything2game.app.data.source.phoneSourceLibrary
import com.anything2game.app.data.tutor.AndroidTutorRepository
import com.anything2game.app.data.tutor.PresetSources
import com.anything2game.app.data.tutor.SourceDocumentStore
import com.anything2game.app.data.tutor.TutorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Everything screens depend on. Screens see only interfaces, so tests and new adapters swap in freely. */
interface AppDependencies {
    val content: ContentLibrary
    val learningProgress: LearningProgress
    val practiceCommits: PracticeStore
    val materials: MaterialCatalog
    val spaces: SpaceRepository
    val generation: GenerationRepository
    val pdfImports: PdfImportQueue
    val tutors: com.anything2game.app.data.tutor.TutorRepository
    val packages: PackageRepository
    val tutorScripts: com.anything2game.app.data.tutor.TutorScripts
    val frames: FrameRepository
    val framePreferences: FramePreferences
    val homePreferences: com.anything2game.app.data.HomePreferences
    val avatarPreferences: AvatarPreferences
    val explainerPreferences: ExplainerPreferences
    val sessions: SessionStore
    val mastery: MasteryStore
    val grading: Grading
    val masteryPolicy: MasteryPolicy
    val contextSource: ContextSource
    val contextPolicy: ContextPolicy
    val clock: Clock
    /** Who is playing (guest / sample student). Per-user stores above follow the active profile. */
    val profiles: ProfileController
    /** Where every source (its text, and its original file when the phone has it) is opened from; one viewer shows what it returns. */
    val sources: SourceLibrary
}

/** Composition root: the only place that picks concrete implementations. */
class AndroidAppContainer(context: Context, private val apiBaseUrl: String = BuildConfig.TUTOR_API) : AppDependencies {
    private val app = context.applicationContext
    // One content library over every pack source: bundled presets first, then packs installed on the device
    // (from our server or imported). Same file format everywhere; the engine sees only the two ports.
    // Content is shared by all profiles; progress is not.
    val installedPacks = DirectoryPackSource(File(app.filesDir, "packs"))
    override val content = ContentLibrary(listOf(AssetPackSource(app.assets), installedPacks))
    override val packages: PackageRepository = content
    override val tutorScripts: com.anything2game.app.data.tutor.TutorScripts = content
    override val frames: FrameRepository = content
    override val grading: Grading = GraderRegistry.default()
    override val masteryPolicy: MasteryPolicy = LeitnerPolicy()
    // Real sensors (Activity Recognition, ConnectivityManager) replace this adapter later; nothing else changes.
    override val contextSource: ContextSource = SimulatedContextSource()
    override val contextPolicy: ContextPolicy = com.anything2game.engine.context.StandardContextPolicy { scope.homePreferences.pauseWhenWalking }
    override val clock: Clock = Clock.SYSTEM

    /** Everything that belongs to one person on this phone, built from the same store classes for every profile. */
    private inner class UserScope(val profile: LocalProfile) {
        private val dir = profile.dir(app)
        val homePreferences = HomePreferences(app, profile.prefs("home"))
        val progress: LearningProgress = LearningProgress(File(dir, "learning.json"), profile.id, clock,
            tutorHistory = { tutors.savedHistory() }, beforeProjection = ::recoverProgress, policy = masteryPolicy,
            legacyStars = homePreferences::legacyStars)
        private suspend fun recoverProgress() {
            practice.recover()
            tutors.recoverPendingReads()
        }
        val practice = PracticeStore(File(dir, "practice-write.json"), FileSessionStore(File(dir, "sessions")),
            FileMasteryStore(File(dir, "mastery")) { content.load(it).getOrNull()?.contentVersion }, progress, clock)
        val sessions: SessionStore = practice
        val mastery: MasteryStore = practice
        private val transport = ApiTransport(app, apiBaseUrl, profile.prefs("tutor_connection"))
        private val http = TutorHttpClient(app, transport)
        private val api = ActivityApi(transport)
        private val authenticated = AuthenticatedCourses(File(dir, "owned-courses.json"), content, transport::localOwnerKey)
        val framePreferences: FramePreferences = SharedPrefsFramePreferences(app, profile.prefs("frames"))
        val avatarPreferences: AvatarPreferences = SharedPrefsAvatarPreferences(app, profile.prefs("avatar"))
        val explainerPreferences: ExplainerPreferences = SharedPrefsExplainerPreferences(app, profile.prefs("explainer"))
        val tutors = AndroidTutorRepository(app, packages, tutorScripts, authenticated::install, File(dir, "tutor-sessions"), profile.prefs("tutor_connection"), File(dir, "source-documents"), http, progress::tutorRead)
        // Same per-profile directory as the imported PDFs, so a lookup by the session's ids finds an attached preset PDF too.
        // Unit tests run with the papers left out (-Da2g.bundledPdfs=off): they exercise "nothing attached", and
        // Robolectric cannot render a PDF page anyway; the bundled path has its own test on PresetSources.
        val presetSources = PresetSources(content, SourceDocumentStore(File(dir, "source-documents"))) { id ->
            if (System.getProperty("a2g.bundledPdfs") == "off") null else runCatching { app.assets.open("sample-pdfs/$id.pdf") }.getOrNull()
        }
        val sources: SourceLibrary = phoneSourceLibrary(tutors, presetSources)
        val materials = MaterialCatalog(content, tutors, presetSources, sessions, authenticated::owns, framePreferences)
        val spaces = SpaceRepository(File(dir, "spaces.json"), materials::materials, api, content::path)
        val generation = GenerationRepository(File(dir, "generation.json"), api, content, materials, spaces)
        val imports = PdfImportQueue(File(dir, "pdf-imports"), http::readPdf, tutors, spaces, clock::now)

    }

    private val registry = ProfileRegistry(app).also { r ->
        // Installs from before profiles existed keep playing as the guest instead of meeting the welcome screen.
        // Only once: the guest's own files live in the same place, so later "Switch profile → welcome" must stay on the welcome screen.
        val legacy = listOf("sessions", "mastery", "tutor-sessions").any { File(app.filesDir, it).list()?.isNotEmpty() == true }
        if (!r.hasChosen() && r.active() == null && legacy) r.setActive(LocalProfile.GUEST)
    }
    @Volatile private var scope = UserScope(registry.active() ?: LocalProfile.GUEST)

    override val materials get() = scope.materials
    override val spaces get() = scope.spaces
    override val generation get() = scope.generation
    override val practiceCommits get() = scope.practice
    override val learningProgress get() = scope.progress
    override val pdfImports get() = scope.imports
    override val tutors: TutorRepository get() = scope.tutors
    override val framePreferences: FramePreferences get() = scope.framePreferences
    override val homePreferences: HomePreferences get() = scope.homePreferences
    override val avatarPreferences: AvatarPreferences get() = scope.avatarPreferences
    override val explainerPreferences: ExplainerPreferences get() = scope.explainerPreferences
    override val sessions: SessionStore get() = scope.sessions
    override val mastery: MasteryStore get() = scope.mastery
    override val sources: SourceLibrary get() = scope.sources

    override val profiles: ProfileController = object : ProfileController {
        private val state = MutableStateFlow(registry.active())
        override val active: StateFlow<LocalProfile?> = state.asStateFlow()

        // A double tap on "Continue as Lin" must not play Lin's history twice (the seed is not idempotent: it adds to mastery).
        private val chooseLock = kotlinx.coroutines.sync.Mutex()

        override suspend fun choose(profile: LocalProfile) = chooseLock.withLock {
            val next = UserScope(profile)
            if (profile.kind == ProfileKind.SAMPLE && !registry.isSeeded(profile)) {
                SampleProfileSeeder(packages, frames, grading, masteryPolicy, next.sessions, next.mastery, next.homePreferences, next.avatarPreferences, next.framePreferences, clock, next.progress, next.materials)
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
