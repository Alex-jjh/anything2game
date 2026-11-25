package com.anything2game.app.ui.space

import com.anything2game.app.ui.common.problemCode
import com.anything2game.app.ui.common.problemText
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.anything2game.app.ui.character.LocalAvatar
import com.anything2game.app.ui.common.StatusBadge
import com.anything2game.app.ui.character.Mood
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.anything2game.engine.progress.ActivityStatus

@Composable
internal fun spaceModel(deps: AppDependencies, language: String, detail: String? = null): SpaceViewModel {
    val profile by deps.profiles.active.collectAsStateWithLifecycle()
    val vm = viewModel(key = "spaces:${profile?.id}:$language:$detail") { SpaceViewModel.of(deps, language, detail) }
    LifecycleResumeEffect(vm) { vm.perform(); onPauseOrDispose {} }
    return vm
}

@Composable
internal fun SpacesHome(deps: AppDependencies, language: String, padding: PaddingValues, nav: Navigator) {
    val vm = spaceModel(deps, language)
    val profile by deps.profiles.active.collectAsStateWithLifecycle()
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
                if (nav.route.value == Route.Home && nav.tab.value == com.anything2game.app.ui.home.HomeTab.SPACES && deps.profiles.active.value == profile) nav.openSpace(created.spaceId) }
        } }
        item { SpaceFeedback(vm) }
        items(vm.cards, key = { it.space.spaceId }) { card ->
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).background(A2g.skin.evidenceSoft, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Layers, null, tint = A2g.skin.evidence)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(card.space.title, style = MaterialTheme.typography.titleLarge)
                        // A Space in another language than the app's says so: its explanations and games are in that language.
                        if (card.contentLanguage != null && card.contentLanguage != language.substringBefore('-').lowercase()) {
                            val name = java.util.Locale.forLanguageTag(card.contentLanguage).let { it.getDisplayLanguage(it) }
                            Text(name, Modifier.testTag("space_language_${card.space.spaceId}").clip(RoundedCornerShape(50)).background(A2g.skin.evidenceSoft)
                                .padding(horizontal = 10.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = A2g.skin.evidence)
                        }
                    }
                }
                Text(stringResource(R.string.spaces_counts, pluralStringResource(R.plurals.spaces_material_count, card.space.members.size, card.space.members.size),
                    pluralStringResource(R.plurals.spaces_activities_complete, card.total, card.completed, card.total)), Modifier.testTag("space_counts_${card.space.spaceId}"))
                LinearProgressIndicator(progress = { if (card.total == 0) 0f else card.completed.toFloat() / card.total }, modifier = Modifier.fillMaxWidth())
                card.next?.let { Text(stringResource(R.string.spaces_next, it.title), color = A2g.skin.inkMuted) }
                OutlinedButton({ nav.openSpace(card.space.spaceId) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open_space_${card.space.spaceId}")) {
                    Text(stringResource(R.string.spaces_open))
                }
            }
        }
        item { TextButton({ vm.perform { vm.spaces.refreshRemote() } }, enabled = !vm.busy) { Text(stringResource(R.string.spaces_sync)) } }
    }
}

/** Items above the first unit on the route tab: back row, title, tabs with the progress strip (sticky), feedback, generation panel. */
private const val ROUTE_HEADER_ITEMS = 5

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SpaceScreen(deps: AppDependencies, language: String, route: Route.Space, nav: Navigator, library: LibraryViewModel) {
    val profile by deps.profiles.active.collectAsStateWithLifecycle()
    val vm = spaceModel(deps, language, route.id)
    val card = vm.cards.firstOrNull()
    val resources = androidx.compose.ui.platform.LocalResources.current
    var rename by rememberSaveable { mutableStateOf(false) }
    var generate by rememberSaveable { mutableStateOf(false) }
    var selectedEvidence by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedStep by rememberSaveable(route.id) { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val routeList = rememberLazyListState()
    val materialList = rememberLazyListState()
    val listState = if (route.materials) materialList else routeList
    var selectedUris by rememberSaveable(route.id) { mutableStateOf(emptyArray<String>()) }
    var selectionId by rememberSaveable(route.id) { mutableStateOf<String?>(null) }
    val pickPdfs = rememberPdfPicker { uris ->
        selectedUris = uris
        selectionId = java.util.UUID.randomUUID().toString()
    }
    if (card != null && route.materials) PdfImportConfirmation(vm, route, selectedUris, selectionId) { batchId ->
        selectedUris = emptyArray()
        selectionId = null
        nav.clearPdfConfirmation(batchId)
    }
    val map = card?.map
    val sheet = map?.steps?.firstOrNull { it.id == selectedStep }
    // Like a course map, open where the learner is: at the unit of the next step, once per visit.
    var positioned by rememberSaveable(route.id) { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The sticky header (tabs and progress) covers the top of the list; the unit is placed just below it.
    var headerPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(map != null, route.materials, headerPx > 0) {
        if (map != null && !route.materials && !positioned && headerPx > 0) {
            positioned = true
            val unit = map.sections.indexOfFirst { s -> s.steps.any { it.state == StepState.CURRENT } }
            if (unit > 0) routeList.requestScrollToItem(ROUTE_HEADER_ITEMS + unit, -(headerPx + with(density) { 12.dp.roundToPx() }))
        }
    }
    BackHandler { if (rename) rename = false else if (selectedStep != null) selectedStep = null else nav.back() }
    fun tapStep(step: RouteStep) {
        when (step.state) {
            StepState.LOCKED -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(
                step.waitingFor?.let { resources.getString(R.string.route_locked_hint, it) } ?: resources.getString(R.string.route_locked_generic)) }
            StepState.UPDATE -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(resources.getString(R.string.route_update_hint)) }
            else -> selectedStep = step.id
        }
    }
    fun start(node: PathNode) = vm.perform {
        val activity = node.activity
        val open = vm.catalog.resolve(activity, language) ?: error("Missing activity")
        if (nav.route.value != route || deps.profiles.active.value != profile) return@perform
        selectedStep = null
        when (open) {
            is OpenActivity.Game -> nav.openGame(open.spec.gameId, open.spec.gameVersion)
            is OpenActivity.Practice -> nav.openPlay(activity.contentId, activity.activityId, open.activity.frame, activity.contentVersion)
            is OpenActivity.Tutor -> {
                val local = vm.materials.find { it.tutorKey == open.sessionKey }?.localPack
                nav.openRead(open.sessionKey, open.conceptId, local?.packageId, local?.contentVersion)
            }
        }
    }
    Scaffold(containerColor = A2g.skin.paper, snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("route_snackbar")) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxSize().testTag(if (route.materials) "space_materials" else "space_route"),
                listState, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    // The tabs and the progress strip stay in reach while the map scrolls; the map opens at the learner's unit.
                    stickyHeader { Column(Modifier.fillMaxWidth().background(A2g.skin.paper).onSizeChanged { headerPx = it.height }.padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!route.materials, { nav.openSpace(route.id) }, { Text(stringResource(R.string.spaces_route)) }, Modifier.heightIn(min = 48.dp).testTag("show_route"))
                            FilterChip(route.materials, { nav.openSpace(route.id, true) }, { Text(stringResource(R.string.spaces_materials)) }, Modifier.heightIn(min = 48.dp).testTag("show_materials"))
                        }
                        if (!route.materials && map != null) ProgressStrip(map, card.space.members.size, vm.weekDays)
                    } }
                }
                item { SpaceFeedback(vm) }
                if (card != null && route.materials) materialItems(deps, vm, card.space, nav, library, pickPdfs)
                if (card != null && !route.materials) {
                    if (map == null) item { EmptyRoute(card.space.members.isEmpty(), { nav.openSpace(route.id, true) }) {
                        RouteGenerator(vm, card.space, route.id, generate, { generate = it }, primary = true) } }
                    else {
                        // Generation progress and its retry belong where the learner is looking; the button itself stays quiet.
                        item { RouteGenerator(vm, card.space, route.id, generate, { generate = it }, primary = false) }
                        var index = 0
                        for (section in map.sections) {
                            val first = index
                            index += section.steps.size
                            item(key = "section-${section.number}-${section.title}") { SectionPath(section, first, ::tapStep) }
                        }
                        if (map.current == null && map.total > 0) item { Text(stringResource(R.string.route_all_done), Modifier.testTag("route_all_done"), color = A2g.skin.inkMuted) }
                    }
                    if (!card.path?.excludedMembers.isNullOrEmpty()) item {
                        Text(stringResource(R.string.spaces_exclusions) + "\n" + card.path.excludedMembers.joinToString("\n", transform = vm::memberTitle))
                    }
                    if (map != null || card.space.members.isNotEmpty()) item { TextButton({ vm.perform { vm.spaces.refreshRemote(); vm.spaces.sync(route.id) } }, enabled = !vm.busy) { Text(stringResource(R.string.spaces_sync)) } }
                    if (vm.remote?.activePathVersion != null && vm.remote?.activePathVersion != card.space.activePathVersion)
                        item { Button({ vm.perform { vm.generation.downloadActivePath(route.id) } }) { Text(stringResource(R.string.spaces_update)) } }
                    if (vm.stale) item { Button({ vm.perform { vm.spaces.rebase(route.id); vm.spaces.sync(route.id) } }) { Text(stringResource(R.string.spaces_rebase)) } }
                    items(vm.rejected.entries.toList()) { (id, err) -> PaperCard {
                        Text(problemText(err.code))
                        TextButton({ vm.perform { vm.spaces.retryRejected(route.id, id); vm.spaces.sync(route.id) } }) { Text(stringResource(R.string.home_retry)) }
                        TextButton({ vm.perform { vm.spaces.withdrawRejected(route.id, id) } }) { Text(stringResource(R.string.spaces_withdraw)) }
                    } }
                }
            }
            if (route.materials) vm.viewing?.let { member -> vm.files.firstOrNull { it.member == member }?.let { file ->
                key(file.member) { MaterialViewer(deps, file) { vm.viewing = null } }
            } }
            var last by remember { mutableStateOf<RouteStep?>(null) }
            if (sheet != null) last = sheet
            AnimatedVisibility(sheet != null, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)).clickable(onClickLabel = stringResource(R.string.route_close)) { selectedStep = null })
            }
            AnimatedVisibility(sheet != null, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                last?.let { step ->
                    val material = vm.materialOf(step.node)
                    val status by produceState(ActivityStatus(step.standing), step.id, vm.cards) { value = vm.statusOf(step) }
                    StepSheet(step, status, sectionAccent(map?.sections?.firstOrNull { s -> s.steps.any { it.id == step.id } }?.number?.minus(1) ?: 0), !vm.busy,
                        onStart = { start(step.node) }, onDismiss = { selectedStep = null }, onSource = { selectedEvidence = step.id })
                    if (selectedEvidence == step.id) step.source?.let { source ->
                        ActivityEvidence(vm.catalog, deps.sources, source.evidence, material, (source.evidence as? EvidenceRef.Pdf)?.let { vm.sourceTitle(step.node, it) }) { selectedEvidence = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteGenerator(vm: SpaceViewModel, space: Space, id: String, generate: Boolean, setGenerate: (Boolean) -> Unit, primary: Boolean) {
    GenerationPanel(vm.generation, { it is GenerationInput.SpacePath && it.spaceId == vm.remote?.spaceId },
        { busy, enqueue ->
            val modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("generate_route")
            if (primary) Button({ setGenerate(true) }, modifier, enabled = !busy && !vm.busy) { Text(stringResource(R.string.route_make)) }
            else TextButton({ setGenerate(true) }, Modifier.heightIn(min = 48.dp).testTag("generate_route"), enabled = !busy && !vm.busy) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.spaces_generate))
            }
            if (generate) AlertDialog(onDismissRequest = { setGenerate(false) }, title = { Text(stringResource(R.string.spaces_generate)) },
                text = { val excluded = space.members.filterIsInstance<SpaceMember.LocalPackRef>()
                    Text(if (excluded.isEmpty()) stringResource(R.string.spaces_included) else stringResource(R.string.spaces_exclusions) + "\n" + excluded.joinToString("\n", transform = vm::memberTitle)) },
                confirmButton = { TextButton({ setGenerate(false); enqueue { vm.pathRequest(id) } }) { Text(stringResource(R.string.spaces_generate)) } },
                dismissButton = { TextButton({ setGenerate(false) }) { Text(stringResource(R.string.import_cancel)) } })
        }, { vm.perform() }, scopeKey = vm.remote?.spaceId, confirmSpaceRetry = { setGenerate(true) })
}

/** A Space with no route yet: the player's character and one clear next action, never a greyed-out button. */
@Composable
private fun EmptyRoute(noMaterials: Boolean, addMaterials: () -> Unit, makeRoute: @Composable () -> Unit) {
    EmptyState("route_empty", stringResource(R.string.route_empty_title), stringResource(if (noMaterials) R.string.route_empty_no_materials else R.string.route_empty_body)) {
        if (noMaterials) Button(addMaterials, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("route_add_materials")) { Text(stringResource(R.string.route_add_materials)) }
        else makeRoute()
    }
}

@Composable
private fun ProgressStrip(map: RouteMap, materials: Int, days: Int) {
    val skin = A2g.skin
    val fraction = if (map.total == 0) 0f else map.done.toFloat() / map.total
    val description = stringResource(R.string.route_progress_description, map.done, map.total)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(skin.card).border(1.dp, skin.outline.copy(alpha = 0.25f), RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
        .semantics(mergeDescendants = true) { contentDescription = description }.testTag("route_progress"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 6.dp.toPx()
                val inset = stroke / 2
                val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                drawArc(skin.outline.copy(alpha = 0.2f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                if (fraction > 0f) drawArc(skin.correct, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Icon(if (map.current == null && map.total > 0) Icons.Filled.EmojiEvents else Icons.Filled.Flag, null, Modifier.size(20.dp), tint = skin.correct)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.route_progress_done, map.done, map.total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("route_progress_text"))
            Text(stringResource(R.string.route_materials_count, materials) + " · " + stringResource(R.string.spaces_week, days), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
        }
    }
}

/** What a tapped node offers: what it is, how long, where it comes from, and one big button. */
@Composable
private fun StepSheet(step: RouteStep, status: ActivityStatus, accent: Color, enabled: Boolean, onStart: () -> Unit, onDismiss: () -> Unit, onSource: () -> Unit) {
    val skin = A2g.skin
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(skin.card)
        .pointerInput(Unit) {}.padding(horizontal = 22.dp, vertical = 18.dp).navigationBarsPadding().testTag("route_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 44.dp, height = 5.dp).background(skin.outline.copy(alpha = 0.4f), RoundedCornerShape(50)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(56.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(step.kind.icon(), null, Modifier.size(30.dp), tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(step.title, Modifier.testTag("route_sheet_title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(step.kind.label() + " · " + stringResource(R.string.route_minutes, step.node.minutes), color = skin.inkMuted, modifier = Modifier.testTag("route_sheet_kind"))
            }
            IconButton(onDismiss, Modifier.testTag("route_sheet_close")) { Icon(Icons.Filled.Close, stringResource(R.string.route_close)) }
        }
        // Where the learner stands with this step, in the same words as everywhere else.
        StatusBadge(status, Modifier.testTag("route_sheet_status"))
        step.source?.let { source ->
            AssistChip(onSource, { Text(stringResource(R.string.spaces_source_page, source.material ?: source.evidence.documentId, source.page)) },
                Modifier.heightIn(min = 48.dp).testTag("route_sheet_source"), leadingIcon = { Icon(Icons.Filled.Description, null, Modifier.size(18.dp)) })
        }
        Button(onStart, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("open_node_${step.id}"), enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White), shape = RoundedCornerShape(16.dp)) {
            Text(stringResource(when {
                step.state == StepState.DONE || step.state == StepState.REVIEW -> R.string.route_review
                status.inProgress -> R.string.route_continue
                else -> R.string.route_start
            }), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun NameEditor(initial: String, dismiss: () -> Unit, submit: (String) -> Unit) {
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
    val problem = if (vm.unavailable) stringResource(R.string.spaces_unavailable) else vm.error?.let { stringResource(R.string.spaces_error, problemText(it)) }
    if (problem != null) {
        Text(problem, color = A2g.skin.wrong)
        OutlinedButton({ vm.perform() }) { Text(stringResource(R.string.home_retry)) }
    }
}
