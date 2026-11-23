package com.anything2game.app.ui.space

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.data.learning.*
import com.anything2game.app.importing.IntentImportParser
import com.anything2game.app.ui.Navigator
import com.anything2game.app.ui.Route
import com.anything2game.app.ui.home.*
import com.anything2game.app.ui.library.*
import com.anything2game.app.ui.importing.importErrorMessage
import com.anything2game.app.ui.play.offeredFrames
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.FrameId
import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.pack.PackOrigin
import com.anything2game.contract.space.*
import com.anything2game.engine.ports.PackageSummary
import kotlinx.coroutines.CancellationException

internal fun LazyListScope.materialItems(deps: AppDependencies, vm: SpaceViewModel, space: Space, nav: Navigator, library: LibraryViewModel, pickPdfs: () -> Unit) {
    item { MaterialActions(vm, space, pickPdfs) }
    items(vm.imports, key = { it.id }) { row ->
        var confirm by rememberSaveable { mutableStateOf(false) }
        PaperCard {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            val unknown = row.error == PdfImportQueue.UPLOAD_OUTCOME_UNKNOWN
            Text(when {
                row.sessionId != null -> stringResource(R.string.spaces_imported)
                unknown -> stringResource(R.string.spaces_unknown)
                row.error != null -> importErrorMessage(row.error)
                else -> stringResource(R.string.spaces_pending)
            })
            if (row.sessionId == null) OutlinedButton({ if (unknown) confirm = true else vm.retryImport(row, false) },
                enabled = !vm.busy) { Text(stringResource(if (unknown) R.string.spaces_upload_again else R.string.home_retry)) }
        }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, text = { Text(stringResource(R.string.spaces_unknown)) },
            confirmButton = { TextButton({ confirm = false; vm.retryImport(row, true) }) { Text(stringResource(R.string.spaces_upload_again)) } },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.import_cancel)) } })
    }
    if (space.members.isEmpty()) item { Text(stringResource(R.string.spaces_no_material)) }
    items(space.members, key = { it.toString() }) { member ->
        val matches = vm.materials.filter { it.member == member || it.localPack == member }
        val uploaded = matches.firstOrNull { it.localPack == null && it.tutorKey != null }
        val courses = matches.filter { it.localPack != null }.distinctBy { it.localPack }
        PaperCard {
            Text(vm.memberTitle(member), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (member is SpaceMember.LocalPackRef) R.string.spaces_local else R.string.spaces_uploaded), color = A2g.skin.inkMuted)
            uploaded?.let { material ->
                val sessionId = requireNotNull(material.tutorKey)
                OutlinedButton({ nav.openRead(sessionId) }, Modifier.fillMaxWidth().testTag("start_explanation_$sessionId")) {
                    Text(stringResource(R.string.tutor_resume))
                }
                if (courses.isEmpty()) OutlinedButton({ vm.perform { library.preparePractice(sessionId) } },
                    enabled = !vm.busy) { Text(stringResource(R.string.tutor_prepare_practice)) }
            }
            courses.forEach { course -> key(course.localPack) {
                if (courses.size > 1) Text("${course.title} · ${course.localPack?.contentVersion}")
                MaterialActivities(deps, course, nav, showTutor = uploaded == null, library = library)
            } }
            TextButton({ vm.perform { vm.spaces.remove(space.spaceId, member) } }, enabled = !vm.busy) { Text(stringResource(R.string.spaces_remove)) }
        }
    }
}

@Composable
internal fun rememberPdfPicker(onSelected: (Array<String>) -> Unit): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val selected = uris.distinct()
        selected.forEach { runCatching { resolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (selected.isNotEmpty()) onSelected(selected.map(Uri::toString).toTypedArray())
    }
    return { picker.launch(arrayOf(IntentImportParser.PDF)) }
}

@Composable
internal fun PdfImportConfirmation(vm: SpaceViewModel, route: Route.Space, selected: Array<String>, selectionId: String?,
    clear: (String) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val parser = remember(resolver) { IntentImportParser(resolver) }
    val batchId = route.batchId ?: selectionId
    if (batchId != null && (route.share != null || selected.isNotEmpty())) {
        val files = remember(route.share, selected) { route.share?.let(::listOf) ?: selected.map { parser.pdf(it.toUri()) } }
        AlertDialog(onDismissRequest = { if (!vm.busy) clear(batchId) }, title = { Text(stringResource(R.string.spaces_confirm)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.spaces_upload_info)); files.forEach { Text(it.title) } } },
            confirmButton = { Button({ vm.importFiles(route.id, files, batchId) { clear(batchId) } }, Modifier.testTag("confirm_pdfs"), enabled = !vm.busy) { Text(stringResource(R.string.spaces_confirm)) } },
            dismissButton = { TextButton({ clear(batchId) }, enabled = !vm.busy) { Text(stringResource(R.string.import_cancel)) } })
    }
}

@Composable
private fun MaterialActions(vm: SpaceViewModel, space: Space, pickPdfs: () -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(pickPdfs, Modifier.fillMaxWidth().testTag("pick_pdfs"), enabled = !vm.busy) { Text(stringResource(R.string.spaces_import)) }
        OutlinedButton({ adding = true }, Modifier.fillMaxWidth().testTag("add_existing"), enabled = !vm.busy) { Text(stringResource(R.string.spaces_add)) }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.spaces_add)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            val available = vm.materials.distinctBy { it.member }.filterNot { it.member in space.members || it.localPack?.let(space.members::contains) == true }
            if (available.isEmpty()) Text(stringResource(R.string.spaces_no_existing))
            available.forEach { m -> TextButton({ adding = false; vm.perform { vm.spaces.add(space.spaceId, m.member) } }) { Text(m.title) } }
        } }, confirmButton = { TextButton({ adding = false }) { Text(stringResource(R.string.action_back)) } })
}

@Composable
private fun MaterialActivities(deps: AppDependencies, material: Material, nav: Navigator, showTutor: Boolean, library: LibraryViewModel) {
    val local = material.localPack
    var entry by remember(material) { mutableStateOf<LibraryEntry?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var explicit by rememberSaveable(local?.packageId, local?.contentVersion) { mutableStateOf<FrameId?>(null) }
    LaunchedEffect(material) {
        try {
            if (local != null) {
                val content = requireNotNull(deps.content)
                val version = ContentVersion(local.packageId, local.contentVersion)
                val course = requireNotNull(content.course(version))
                val pkg = course.pkg
                val manifest = course.manifest
                val frames = offeredFrames(pkg, content.available(version))
                val summary = PackageSummary(pkg.packageId, pkg.title, pkg.language, pkg.levels.size, manifest.origin == PackOrigin.PRESET)
                entry = libraryEntry(summary, pkg, deps.sessions.load(local.packageId, local.contentVersion),
                    deps.mastery.all(local.packageId, local.contentVersion), deps.clock.now()).copy(
                    frames = frames, selectedFrame = deps.framePreferences.get(local.packageId)?.takeIf { it in frames } ?: FrameId.CLASSIC,
                    tutorProgress = deps.tutors.progressFor(local.packageId, local.contentVersion))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    error?.let { Text(stringResource(R.string.spaces_error, it)) }
    material.tutorKey?.takeIf { showTutor }?.let { key ->
        OutlinedButton({ nav.openRead(key, sourcePackage = local?.packageId, version = local?.contentVersion) },
            Modifier.fillMaxWidth().testTag("start_explanation_${local?.packageId ?: key}")) {
            Text(if (local != null) learnActionLabel(entry?.tutorProgress) else stringResource(R.string.tutor_resume))
        }
    }
    if (local != null && material.tutorKey == null) OutlinedButton({ nav.openSource(local.packageId, local.contentVersion) },
        Modifier.fillMaxWidth().testTag("read_source_${local.packageId}")) { Text(stringResource(R.string.material_read_all)) }
    entry?.let { e ->
        if (e.frames.size > 1) PlayAs(e.frames, explicit ?: e.selectedFrame) { frame -> explicit = frame; library.selectFrame(e.summary.packageId, frame) }
        e.levels.forEach { level ->
            HorizontalDivider()
            Text(level.title, style = MaterialTheme.typography.titleMedium)
            val status = level.status
            Text(when(status) {
                LevelStatus.New -> stringResource(R.string.level_status_new)
                LevelStatus.NeedsReview -> stringResource(R.string.home_needs_review)
                LevelStatus.Done -> stringResource(R.string.home_level_solid)
                LevelStatus.Cleared -> stringResource(R.string.level_status_cleared)
                is LevelStatus.InProgress -> stringResource(R.string.level_status_in_progress, status.position, status.total)
            }, Modifier.testTag("level_status_${e.summary.packageId}_${level.levelId}"))
            Button({ nav.openPlay(e.summary.packageId, level.levelId, explicit ?: e.frameFor(level.levelId), local?.contentVersion, explicit == null) },
                Modifier.fillMaxWidth().testTag("play_${e.summary.packageId}_${level.levelId}")) {
                Text(stringResource(if (status is LevelStatus.InProgress) R.string.home_resume else R.string.home_practice_action))
            }
        }
    }
}
