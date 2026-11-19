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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.contract.FrameId
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.platform.SimulatedContextSource
import com.anything2game.app.ui.common.ContextSimulator
import com.anything2game.app.ui.importing.ImportContent
import com.anything2game.app.ui.library.LibraryContent
import com.anything2game.app.ui.library.LibraryViewModel
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.play.PlayViewModel
import com.anything2game.app.ui.theme.A2g
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Route {
    data object Library : Route
    data class Play(val packageId: String, val levelId: String, val frameId: FrameId) : Route
    data class Import(val request: ImportRequest) : Route
}

/** Three screens don't need a navigation library. The open level survives process death via SavedStateHandle. */
class Navigator(private val saved: SavedStateHandle) : ViewModel() {
    private val _route = MutableStateFlow(restore())
    val route: StateFlow<Route> = _route.asStateFlow()

    private fun restore(): Route {
        val pkg = saved.get<String>(KEY_PKG)
        val level = saved.get<String>(KEY_LEVEL)
        val frame = saved.get<String>(KEY_FRAME)?.let { v -> FrameId.entries.firstOrNull { it.name == v } } ?: FrameId.CLASSIC
        return if (pkg != null && level != null) Route.Play(pkg, level, frame) else Route.Library
    }

    fun openPlay(packageId: String, levelId: String, frameId: FrameId) {
        saved[KEY_PKG] = packageId; saved[KEY_LEVEL] = levelId; saved[KEY_FRAME] = frameId.name
        _route.value = Route.Play(packageId, levelId, frameId)
    }

    fun openImport(request: ImportRequest) { _route.value = Route.Import(request) }

    fun toLibrary() {
        saved.remove<String>(KEY_PKG); saved.remove<String>(KEY_LEVEL); saved.remove<String>(KEY_FRAME)
        _route.value = Route.Library
    }

    private companion object { const val KEY_PKG = "route.pkg"; const val KEY_LEVEL = "route.level"; const val KEY_FRAME = "route.frame" }
}

@Composable
fun AppRoot(deps: AppDependencies, navigator: Navigator, onPickPdf: () -> Unit) {
    val route by navigator.route.collectAsStateWithLifecycle()
    val simulator = simulatorFor(deps)
    var avatarId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(deps.avatarPreferences.get() ?: Avatars.default.id) }
    androidx.compose.runtime.CompositionLocalProvider(LocalAvatar provides Avatars.of(avatarId)) {
    when (val r = route) {
        Route.Library -> {
            val vm = viewModel { LibraryViewModel(deps) }
            LaunchedEffect(Unit) { vm.refresh() }
            val state by vm.state.collectAsStateWithLifecycle()
            Scaffold(containerColor = A2g.skin.paper) { padding ->
                LibraryContent(state, onOpenLevel = navigator::openPlay, onSelectFrame = vm::selectFrame, onPickPdf = onPickPdf,
                    avatarId = avatarId, onSelectAvatar = { avatarId = it; deps.avatarPreferences.set(it) }, footer = { simulator?.invoke() }, contentPadding = padding)
            }
        }
        is Route.Play -> {
            val vm = viewModel(key = "play:${r.packageId}:${r.levelId}:${r.frameId}") { PlayViewModel(deps, r.packageId, r.levelId, r.frameId) }
            val state by vm.state.collectAsStateWithLifecycle()
            when (val s = state) {
                PlayUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is PlayUiState.Failed -> {
                    BackHandler(onBack = navigator::toLibrary)
                    Text(stringResource(R.string.load_failed, s.reason), Modifier.padding(24.dp))
                }
                is PlayUiState.Ready -> PlayContent(s, onAction = vm::dispatch, onBack = navigator::toLibrary, onRestart = vm::restart, simulator = simulator)
            }
        }
        is Route.Import -> {
            BackHandler(onBack = navigator::toLibrary)
            Scaffold(containerColor = A2g.skin.paper) { padding -> ImportContent(r.request, onCancel = navigator::toLibrary, contentPadding = padding) }
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
