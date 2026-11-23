package com.anything2game.app.ui.space

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.data.learning.*
import com.anything2game.app.ui.Navigator
import com.anything2game.app.ui.Route
import com.anything2game.app.ui.game.GenerationPanel
import com.anything2game.app.ui.home.PaperCard
import com.anything2game.app.ui.library.LibraryViewModel
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.game.*
import com.anything2game.contract.space.*
import com.anything2game.engine.progress.NodeStatus

@Composable
private fun spaceModel(deps: AppDependencies, language: String, detail: String? = null): SpaceViewModel {
    val vm = viewModel(key = "spaces:${deps.profiles.active.value?.id}:$language:$detail") { SpaceViewModel(deps, language, detail) }
    LifecycleResumeEffect(vm) { vm.perform(); onPauseOrDispose {} }
    return vm
}

@Composable
internal fun SpacesHome(deps: AppDependencies, language: String, padding: PaddingValues, nav: Navigator) {
    val vm = spaceModel(deps, language)
    val profile = deps.profiles.active.value
    var creating by rememberSaveable { mutableStateOf(false) }
    BackHandler(creating) { creating = false }
    LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("home_SPACES"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(stringResource(R.string.nav_spaces), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.spaces_week, vm.weekDays), color = A2g.skin.inkMuted)
        }
        item { Button({ creating = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("space_create"), enabled = !vm.busy) {
            Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.spaces_new))
        } }
        if (creating) item { NameEditor("", { creating = false }) { title ->
            creating = false
            vm.perform { val created = vm.spaces.create(title)
                if (nav.route.value == Route.Home && nav.tab.value == com.anything2game.app.ui.home.HomeTab.SPACES && deps.profiles.active.value == profile) nav.openSpace(created.spaceId, true) }
        } }
        item { SpaceFeedback(vm) }
        items(vm.cards, key = { it.space.spaceId }) { card ->
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).background(A2g.skin.evidenceSoft, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Layers, null, tint = A2g.skin.evidence)
                    }
                    Text(card.space.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                val count = card.path?.nodes?.size ?: 0
                Text(stringResource(R.string.spaces_counts, card.space.members.size, card.completed, count))
                LinearProgressIndicator(progress = { if (count == 0) 0f else card.completed.toFloat()/count }, modifier = Modifier.fillMaxWidth())
                card.next?.let { Text(stringResource(R.string.spaces_next, it.title), color = A2g.skin.inkMuted) }
                OutlinedButton({ nav.openSpace(card.space.spaceId) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open_space_${card.space.spaceId}")) {
                    Text(stringResource(R.string.spaces_open))
                }
            }
        }
        item { TextButton({ vm.perform { vm.spaces.refreshRemote() } }, enabled = !vm.busy) { Text(stringResource(R.string.spaces_sync)) } }
    }
}

@Composable
internal fun SpaceScreen(deps: AppDependencies, language: String, route: Route.Space, nav: Navigator, library: LibraryViewModel) {
    val profile = deps.profiles.active.value
    val vm = spaceModel(deps, language, route.id)
    val card = vm.cards.firstOrNull()
    var rename by rememberSaveable { mutableStateOf(false) }
    var generate by rememberSaveable { mutableStateOf(false) }
    var selectedEvidence by rememberSaveable { mutableStateOf<String?>(null) }
    val routeList = rememberLazyListState()
    val materialList = rememberLazyListState()
    val listState = if (route.materials) materialList else routeList
    BackHandler { if (rename) rename = false else nav.back() }
    Scaffold(containerColor = A2g.skin.paper) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(if (route.materials) "space_materials" else "space_route"),
            listState, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(nav::back) { Text(stringResource(R.string.action_back)) }
                TextButton({ rename = true }, enabled = card != null && !vm.busy) { Text(stringResource(R.string.spaces_rename)) }
            } }
            if (card != null) {
                item { Text(card.space.title, style = MaterialTheme.typography.headlineMedium) }
                if (rename) item { NameEditor(card.space.title, { rename = false }) { name ->
                    rename = false
                    vm.perform { vm.spaces.rename(route.id, name) }
                } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!route.materials, { nav.openSpace(route.id) }, { Text(stringResource(R.string.spaces_route)) }, Modifier.heightIn(min = 48.dp).testTag("show_route"))
                    FilterChip(route.materials, { nav.openSpace(route.id, true) }, { Text(stringResource(R.string.spaces_materials)) }, Modifier.heightIn(min = 48.dp).testTag("show_materials"))
                } }
            }
            item { SpaceFeedback(vm) }
            if (card != null && route.materials) materialItems(deps, vm, card.space, nav, library)
            if (card != null && !route.materials) {
                item { Text(stringResource(R.string.spaces_counts, card.space.members.size, card.completed, card.path?.nodes?.size ?: 0)) }
                item { GenerationPanel(vm.generation, { it is GenerationInput.SpacePath && it.spaceId == vm.remote?.spaceId },
                    { busy, enqueue ->
                        OutlinedButton({ generate = true }, Modifier.fillMaxWidth().testTag("generate_route"), enabled = !busy && !vm.busy) { Text(stringResource(R.string.spaces_generate)) }
                        if (generate) AlertDialog(onDismissRequest = { generate = false }, title = { Text(stringResource(R.string.spaces_generate)) },
                            text = { val excluded = card.space.members.filterIsInstance<SpaceMember.LocalPackRef>()
                                Text(if (excluded.isEmpty()) stringResource(R.string.spaces_included) else stringResource(R.string.spaces_exclusions) + "\n" + excluded.joinToString("\n", transform = vm::memberTitle)) },
                            confirmButton = { TextButton({ generate = false; enqueue { vm.pathRequest(route.id) } }) { Text(stringResource(R.string.spaces_generate)) } },
                            dismissButton = { TextButton({ generate = false }) { Text(stringResource(R.string.import_cancel)) } })
                    }, { vm.perform() }, scopeKey = vm.remote?.spaceId)
                }
                if (!card.path?.excludedMembers.isNullOrEmpty()) item {
                    Text(stringResource(R.string.spaces_exclusions) + "\n" + card.path.excludedMembers.joinToString("\n", transform = vm::memberTitle))
                }
                if (card.path == null) item { Text(stringResource(R.string.spaces_empty)) }
                items(card.path?.nodes.orEmpty(), key = { it.nodeId }) { node ->
                    val status = card.status[node.nodeId] ?: NodeStatus.NEEDS_UPDATE
                    PaperCard {
                        node.chapter?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        Text(node.title, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(statusLabel(status)), color = A2g.skin.inkMuted, modifier = Modifier.testTag("route_status_${node.nodeId}"))
                        val activity = node.activity
                        Button({ vm.perform {
                            val open = vm.catalog.resolve(activity, language) ?: error("Missing activity")
                            if (nav.route.value != route || deps.profiles.active.value != profile) return@perform
                            when (open) {
                                is OpenActivity.Game -> nav.openGame(open.spec.gameId, open.spec.gameVersion)
                                is OpenActivity.Practice -> nav.openPlay(activity.contentId, activity.activityId, open.activity.frame, activity.contentVersion)
                                is OpenActivity.Tutor -> {
                                    val local = vm.materials.find { it.tutorKey == open.sessionKey }?.localPack
                                    nav.openRead(open.sessionKey, open.conceptId, local?.packageId, local?.contentVersion)
                                }
                            }
                        } }, Modifier.heightIn(min = 48.dp).testTag("open_node_${node.nodeId}"),
                            enabled = !vm.busy && status != NodeStatus.LOCKED && status != NodeStatus.NEEDS_UPDATE) {
                            Text(stringResource(when (activity) { is ActivityRef.Tutor -> R.string.spaces_explain; is ActivityRef.Practice -> R.string.spaces_light; is ActivityRef.Game -> R.string.spaces_challenge }))
                        }
                        node.evidence.forEach { ref ->
                            val id = node.nodeId + "/" + ref.evidenceId
                            TextButton({ selectedEvidence = id }) {
                                Text(if (ref is EvidenceRef.Pdf) stringResource(R.string.spaces_source_page,
                                    vm.materials.find { it.source == ContentVersion(ref.documentId, ref.documentVersion) }?.title ?: ref.documentId,
                                    ref.pageLabel ?: (ref.pageIndex+1).toString()) else stringResource(R.string.source_open))
                            }
                            if (selectedEvidence == id) ActivityEvidence(vm.catalog, ref) { selectedEvidence = null }
                        }
                    }
                }
                item { TextButton({ vm.perform { vm.spaces.refreshRemote(); vm.spaces.sync(route.id) } }, enabled = !vm.busy) { Text(stringResource(R.string.spaces_sync)) } }
                if (vm.remote?.activePathVersion != null && vm.remote?.activePathVersion != card.space.activePathVersion)
                    item { Button({ vm.perform { vm.generation.downloadActivePath(route.id) } }) { Text(stringResource(R.string.spaces_update)) } }
                if (vm.stale) item { Button({ vm.perform { vm.spaces.rebase(route.id); vm.spaces.sync(route.id) } }) { Text(stringResource(R.string.spaces_rebase)) } }
                items(vm.rejected.entries.toList()) { (id, err) -> PaperCard {
                    Text(err.message)
                    TextButton({ vm.perform { vm.spaces.retryRejected(route.id, id); vm.spaces.sync(route.id) } }) { Text(stringResource(R.string.home_retry)) }
                    TextButton({ vm.perform { vm.spaces.withdrawRejected(route.id, id) } }) { Text(stringResource(R.string.spaces_withdraw)) }
                } }
            }
        }
    }
}

@Composable
private fun NameEditor(initial: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    PaperCard {
        OutlinedTextField(text, { text = it.take(120) }, Modifier.fillMaxWidth().testTag("space_name"), singleLine = true,
            label = { Text(stringResource(R.string.spaces_name)) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ submit(text.trim()) }, Modifier.testTag("save_space"), enabled = text.isNotBlank()) { Text(stringResource(R.string.home_save)) }
            TextButton(dismiss) { Text(stringResource(R.string.import_cancel)) }
        }
    }
}

@Composable
private fun SpaceFeedback(vm: SpaceViewModel) {
    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    vm.error?.let { Text(stringResource(R.string.spaces_error, it), color = A2g.skin.wrong)
        OutlinedButton({ vm.perform() }) { Text(stringResource(R.string.home_retry)) } }
}

private fun statusLabel(status: NodeStatus) = when (status) {
    NodeStatus.AVAILABLE -> R.string.spaces_available
    NodeStatus.LOCKED -> R.string.spaces_locked
    NodeStatus.NEEDS_UPDATE -> R.string.spaces_update_needed
    NodeStatus.COMPLETE -> R.string.spaces_complete
    NodeStatus.REVIEW -> R.string.spaces_review
}
