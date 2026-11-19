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
import com.anything2game.app.platform.SimulatedContextSource
import com.anything2game.app.ui.common.ContextSimulator
import com.anything2game.app.ui.importing.ImportContent
import com.anything2game.app.ui.home.HomeContent
import com.anything2game.app.ui.home.HomeNavigation
import com.anything2game.app.ui.home.HomeTab
import com.anything2game.app.ui.reading.ReadingContent
import com.anything2game.app.ui.library.LibraryViewModel
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.play.PlayViewModel
import com.anything2game.app.ui.theme.A2g

@Composable
fun AppRoot(deps: AppDependencies, navigator: Navigator, onPickPdf: () -> Unit) {
    val route by navigator.route.collectAsStateWithLifecycle()
    val tab by navigator.tab.collectAsStateWithLifecycle()
    val simulator = simulatorFor(deps)
    var avatarId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(deps.avatarPreferences.get() ?: Avatars.default.id) }
    var nickname by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(deps.homePreferences.nickname) }
    val tabsState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    LaunchedEffect(navigator) { navigator.initializeTab(deps.homePreferences.lastTab) }
    val selectTab: (HomeTab) -> Unit = { navigator.selectTab(it); deps.homePreferences.lastTab = it.name }
    androidx.compose.runtime.CompositionLocalProvider(LocalAvatar provides Avatars.of(avatarId)) {
    when (val r = route) {
        Route.Home -> {
            val vm = viewModel { LibraryViewModel(deps) }
            LaunchedEffect(Unit) { vm.refresh() }
            val state by vm.state.collectAsStateWithLifecycle()
            BackHandler(enabled = tab != HomeTab.LEARN) { selectTab(HomeTab.LEARN) }
            Scaffold(containerColor = A2g.skin.paper, bottomBar = { HomeNavigation(tab, selectTab) }) { padding ->
                tabsState.SaveableStateProvider(tab.name) {
                    HomeContent(tab, state, avatarId, nickname,
                        onSelectAvatar = { avatarId = it; deps.avatarPreferences.set(it) },
                        onRename = { nickname = it; deps.homePreferences.nickname = it },
                        onPickPdf = onPickPdf, onRead = navigator::openRead, onPlay = navigator::openPlay,
                        onSelectTab = selectTab, onRetry = { vm.refresh() }, padding = padding, simulator = simulator, onPreparePractice = vm::preparePractice)
                }
            }
        }
        is Route.Read -> {
            ReadingContent(deps, r.packageId, onBack = navigator::toHome)
        }
        is Route.Play -> {
            val vm = viewModel(key = "play:${r.packageId}:${r.levelId}:${r.frameId}") { PlayViewModel(deps, r.packageId, r.levelId, r.frameId) }
            val state by vm.state.collectAsStateWithLifecycle()
            when (val s = state) {
                PlayUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is PlayUiState.Failed -> {
                    BackHandler(onBack = navigator::toHome)
                    Text(stringResource(R.string.load_failed, s.reason), Modifier.padding(24.dp))
                }
                is PlayUiState.Ready -> PlayContent(s, onAction = vm::dispatch, onBack = navigator::toHome, onRestart = vm::restart, simulator = simulator)
            }
        }
        is Route.Import -> {
            val vm: ImportViewModel = viewModel(key = "import:" + java.security.MessageDigest.getInstance("SHA-256").digest(r.request.toString().toByteArray()).joinToString("") { "%02x".format(it) }, factory = viewModelFactory {
                initializer { ImportViewModel(deps.tutors, r.request, createSavedStateHandle()) }
            })
            val state by vm.state.collectAsStateWithLifecycle()
            val cancel = { vm.cancel(); navigator.toHome() }
            BackHandler(onBack = cancel)
            LaunchedEffect(state.ready) { state.ready?.let(navigator::openRead) }
            Scaffold(containerColor = A2g.skin.paper) { padding ->
                ImportContent(r.request, onCancel = cancel, contentPadding = padding, endpoint = deps.tutors.endpoint,
                    state = state, onStart = vm::start, onDemo = { vm.cancel(); navigator.openRead(com.anything2game.app.data.tutor.FIXED_TUTOR_PACKAGE) })
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
