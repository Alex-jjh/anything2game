package com.anything2game.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.anything2game.app.ui.character.Avatars
import com.anything2game.app.ui.character.LocalAvatar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anything2game.app.ui.importing.ImportViewModel
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.ui.reading.MaterialReader
import com.anything2game.app.ui.space.SharedPdfDestination
import com.anything2game.app.platform.SimulatedContextSource
import com.anything2game.app.ui.common.ContextSimulator
import com.anything2game.app.ui.importing.ImportContent
import com.anything2game.app.ui.game.GameScreen
import com.anything2game.app.ui.game.Playground
import com.anything2game.app.ui.space.SpacesHome
import com.anything2game.app.ui.space.SpaceScreen
import com.anything2game.contract.game.ContentVersion
import com.anything2game.app.ui.home.ProfileContent
import com.anything2game.app.ui.home.HomeNavigation
import com.anything2game.app.ui.home.HomeTab
import com.anything2game.app.ui.reading.ReadingContent
import com.anything2game.app.ui.library.LibraryViewModel
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.play.PlayViewModel
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.home.WelcomeScreen
import kotlinx.coroutines.launch

@Composable
fun AppRoot(deps: AppDependencies, navigator: Navigator) {
    val route by navigator.route.collectAsStateWithLifecycle()
    val tab by navigator.tab.collectAsStateWithLifecycle()
    val simulator = simulatorFor(deps)
    // Who is playing: until a profile is chosen, the welcome screen shows. Every per-user store follows the profile.
    val profile by deps.profiles.active.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val current = profile ?: run {
        WelcomeScreen(onChoose = { p -> scope.launch { deps.profiles.choose(p) } })
        return
    }
    androidx.compose.runtime.key(current.id) {
    var avatarId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(deps.avatarPreferences.get() ?: Avatars.default.id) }
    var nickname by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(deps.homePreferences.nickname) }
    var pauseWalking by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(deps.homePreferences.pauseWhenWalking) }
    val tabsState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    LaunchedEffect(navigator, current.id) { navigator.initializeTab(deps.homePreferences.lastTab); deps.homePreferences.lastTab = navigator.tab.value.name }
    val language = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "zh") "zh" else "en"
    val library = viewModel(key = "library:${current.id}") { LibraryViewModel(deps) }
    // Demo controls: the context simulator, and the real (server) game generation, which the Playground itself no longer offers.
    val profileTools: (@Composable () -> Unit)? = simulator?.let { sim ->
        { sim(); com.anything2game.app.ui.game.DeveloperGameTools(deps, language) { navigator.openGame(it.contentId, it.contentVersion) } }
    }
    val selectTab: (HomeTab) -> Unit = { navigator.selectTab(it); deps.homePreferences.lastTab = it.name }
    androidx.compose.runtime.CompositionLocalProvider(LocalAvatar provides Avatars.of(avatarId)) {
    when (val r = route) {
        Route.Home -> {
            BackHandler(enabled = tab != HomeTab.SPACES) { selectTab(HomeTab.SPACES) }
            Scaffold(containerColor = A2g.skin.paper, bottomBar = { HomeNavigation(tab, selectTab) }) { padding ->
                tabsState.SaveableStateProvider(tab.name) {
                    when (tab) {
                        HomeTab.PLAYGROUND -> Playground(deps, language, padding) { navigator.openGame(it.contentId, it.contentVersion) }
                        HomeTab.SPACES -> SpacesHome(deps, language, padding, navigator)
                        HomeTab.PROFILE -> {
                            LaunchedEffect(library) { library.refresh() }
                            val state by library.state.collectAsStateWithLifecycle()
                            ProfileContent(state, avatarId, nickname, onSelectAvatar = { avatarId = it; deps.avatarPreferences.set(it) },
                                onRename = { nickname = it; deps.homePreferences.nickname = it }, onSpaces = { selectTab(HomeTab.SPACES) },
                                onRetry = { library.refresh() }, padding = padding, simulator = profileTools,
                                onSwitchProfile = { navigator.resetProfile(); deps.profiles.leave() },
                                pauseWhenWalking = pauseWalking, onPauseWhenWalking = { pauseWalking = it; deps.homePreferences.pauseWhenWalking = it })
                        }
                    }
                }
            }
        }
        is Route.Space -> tabsState.SaveableStateProvider("space:${r.id}") { SpaceScreen(deps, language, r, navigator, library) }
        is Route.Game -> GameScreen(deps, ContentVersion(r.id, r.version), navigator::back)
        is Route.Source -> MaterialReader(deps.packages, r.packageId, r.version, navigator::back)
        is Route.Read -> {
            ReadingContent(deps, r.packageId, r.conceptId, r.sourcePackage ?: r.packageId, r.contentVersion, navigator::back)
        }
        is Route.Play -> {
            val vm = viewModel(key = "play:${current.id}:${r.packageId}:${r.version}:${r.levelId}:${r.frameId}:${r.run}") { PlayViewModel(deps, r.packageId, r.levelId, r.frameId, r.version, r.resumeFrame) }
            // A level that is no longer on screen must not keep reacting to the phone's sensors (it would rewrite the course's one saved session).
            androidx.compose.runtime.DisposableEffect(vm) { vm.listenToContext(); onDispose { vm.stopListeningToContext() } }
            val state by vm.state.collectAsStateWithLifecycle()
            when (val s = state) {
                PlayUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is PlayUiState.Failed -> {
                    BackHandler(onBack = navigator::back)
                    Text(stringResource(R.string.load_failed, s.reason), Modifier.padding(24.dp))
                }
                is PlayUiState.Ready -> {
                    // The same file Show source uses: a preset's attached or bundled paper, else a PDF the reader imported.
                    val pkg = s.pkg
                    val pdf by androidx.compose.runtime.produceState<com.anything2game.app.data.tutor.SourceDocument?>(null, current.id, pkg.packageId, pkg.documentVersion) {
                        value = deps.presetSources?.load(pkg.packageId, pkg.documentId, pkg.documentVersion, pkg.contentVersion)
                            ?: runCatching { deps.tutors.sourceDocument(pkg.documentId, pkg.documentVersion) }.getOrNull()
                    }
                    PlayContent(s, onAction = vm::dispatch, onBack = navigator::back, onRestart = vm::restart, simulator = simulator, sourcePdf = pdf)
                }
            }
        }
        is Route.Import -> if (r.request is ImportRequest.PdfFile) {
            SharedPdfDestination(deps, language, r.request.title, onChoose = { id ->
                if (navigator.route.value == r && deps.profiles.active.value == current) {
                    selectTab(HomeTab.SPACES)
                    navigator.openSpaceForPdf(id, r.request)
                }
            }, onBack = navigator::back)
        } else {
            val vm: ImportViewModel = viewModel(key = "import:${current.id}:" + java.security.MessageDigest.getInstance("SHA-256").digest(r.request.toString().toByteArray()).joinToString("") { "%02x".format(it) }, factory = viewModelFactory {
                initializer { ImportViewModel(deps.tutors, r.request, createSavedStateHandle()) }
            })
            val state by vm.state.collectAsStateWithLifecycle()
            val cancel = { vm.cancel(); navigator.toHome() }
            BackHandler(onBack = cancel)
            suspend fun openImported(id: String) {
                val catalog = requireNotNull(deps.materials).materials()
                val source = catalog.firstOrNull { it.tutorKey == id || (it.member as? com.anything2game.contract.space.SpaceMember.LocalPackRef)?.packageId == id }
                val parent = requireNotNull(deps.spaces).list().firstOrNull { source?.member in it.members }
                navigator.selectTab(HomeTab.SPACES)
                if (parent != null && source != null) {
                    navigator.openSpace(parent.spaceId, true)
                    navigator.openRead(source.tutorKey ?: id, sourcePackage = source.localPack?.packageId, version = source.localPack?.contentVersion)
                }
            }
            LaunchedEffect(state.ready) { state.ready?.let { openImported(it) } }
            Scaffold(containerColor = A2g.skin.paper) { padding ->
                ImportContent(r.request, onCancel = cancel, contentPadding = padding, endpoint = deps.tutors.endpoint,
                    state = state, onStart = vm::start, onDemo = { vm.cancel(); scope.launch { openImported(com.anything2game.app.data.tutor.EXAMPLE_TUTOR_PACKAGE) } })
            }
        }
    }
    }
    }
}

/** The simulator appears only when the context source is the simulated one (debug / demo builds). */
private fun simulatorFor(deps: AppDependencies): (@Composable () -> Unit)? {
    val source = deps.contextSource as? SimulatedContextSource ?: return null
    return {
        val signals by source.signals.collectAsStateWithLifecycle()
        ContextSimulator(signals, onActivity = source::setActivity, onNetwork = source::setNetwork)
    }
}
