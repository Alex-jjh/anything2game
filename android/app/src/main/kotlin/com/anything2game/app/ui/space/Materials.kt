package com.anything2game.app.ui.space

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.anything2game.app.ui.common.problemCode
import com.anything2game.app.ui.common.problemText
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.anything2game.app.ui.character.LocalAvatar
import com.anything2game.app.ui.character.Mood
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
import com.anything2game.app.data.source.CourseRef
import com.anything2game.app.data.source.SourceKey
import com.anything2game.app.data.source.SourceText
import com.anything2game.app.data.tutor.TutorProgress
import com.anything2game.app.importing.IntentImportParser
import com.anything2game.app.ui.source.OpenSourceViewer
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
import com.anything2game.app.ui.common.StatusBadge
import com.anything2game.engine.progress.Standing
import com.anything2game.engine.ports.PackageSummary
import kotlinx.coroutines.CancellationException

/** The Materials tab: the Space's files as a phone's file list. A tap opens the original; the menu on each row keeps the other actions. */
internal fun LazyListScope.materialItems(deps: AppDependencies, vm: SpaceViewModel, space: Space, nav: Navigator, library: LibraryViewModel, pickPdfs: () -> Unit) {
    val pending = vm.imports.filter { it.sessionId == null }
    if (space.members.isEmpty() && pending.isEmpty()) {
        item { EmptyMaterials(vm, space, pickPdfs) }
        return
    }
    item { MaterialActions(vm, space, pickPdfs) }
    items(pending, key = { it.id }) { row -> ImportRow(vm, row) }
    items(space.members, key = { it.toString() }) { member ->
        // Until the file's details are read, the row already shows its name (and no facts), so the list never pops in.
        val read = vm.files.firstOrNull { it.member == member }
        val file = read ?: vm.memberTitle(member).let { name ->
            MaterialFile(member, name, if (name.lowercase().endsWith(".pdf")) FileKind.PDF else FileKind.TEXT, null, null, emptyList(), member.version(), null)
        }
        val matches = vm.materials.filter { it.member == member || it.localPack == member }
        val uploaded = matches.firstOrNull { it.localPack == null && it.tutorKey != null }
        val courses = matches.filter { it.localPack != null }.distinctBy { it.localPack }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FileRow(deps, vm, file, read != null, uploaded, courses, space, nav, library)
            if (member in vm.practising) courses.forEach { course -> key(course.localPack) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp).testTag("practice_${memberTag(member)}")) {
                    PaperCard {
                        if (courses.size > 1) Text(course.title)
                        PracticeList(deps, course, nav, library)
                    }
                }
            } }
        }
    }
}

/** The id the tags of a file's row are built from: the lesson's own id, as the Learn tab uses. */
private fun memberTag(member: SpaceMember) = when (member) {
    is SpaceMember.ServerDocumentRef -> member.documentId
    is SpaceMember.LocalPackRef -> member.packageId
}

@Composable
private fun kindIcon(kind: FileKind) = when (kind) {
    FileKind.PDF -> Icons.Outlined.PictureAsPdf
    FileKind.SLIDES -> Icons.Outlined.Slideshow
    FileKind.TEXT -> Icons.Outlined.Description
    FileKind.COURSE -> Icons.Outlined.School
}

@Composable
private fun kindName(kind: FileKind) = stringResource(when (kind) {
    FileKind.PDF -> R.string.file_kind_pdf
    FileKind.SLIDES -> R.string.file_kind_slides
    FileKind.TEXT -> R.string.file_kind_text
    FileKind.COURSE -> R.string.file_kind_course
})

/** "PDF · 15 pages · added 23 Nov": only what is known, plainly. */
@Composable
private fun facts(kind: FileKind, pages: Int?, chapters: Int?, size: Long?, addedAt: Long): String {
    val context = LocalContext.current
    return listOfNotNull(
        kindName(kind),
        pages?.let { pluralStringResource(R.plurals.file_pages, it, it) },
        chapters?.let { pluralStringResource(R.plurals.file_chapters, it, it) },
        size?.takeIf { pages == null && it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
        addedAt.takeIf { it > 0 }?.let { stringResource(R.string.file_added, DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)) },
    ).joinToString(" · ")
}

/** One file-manager row: icon, name, plain facts, a status only when it matters, and a menu for everything else. */
@Composable
private fun FileLine(kind: FileKind, title: String, facts: String, status: String?, statusColor: Color, tag: String, open: () -> Unit, menu: @Composable () -> Unit) {
    val skin = A2g.skin
    val (tint, soft) = if (kind == FileKind.COURSE) skin.correct to skin.correctSoft else skin.evidence to skin.evidenceSoft
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(skin.card).border(1.dp, skin.outline.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 72.dp).clickable(onClickLabel = stringResource(R.string.material_open), onClick = open).padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
            .testTag("material_row_$tag"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(soft, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(kindIcon(kind), null, tint = tint) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = skin.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("material_title_$tag"))
                if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted, modifier = Modifier.testTag("material_facts_$tag"))
                if (status != null) Text(status, style = MaterialTheme.typography.bodyMedium, color = statusColor, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("material_status_$tag"))
            }
        }
        menu()
    }
}

@Composable
private fun FileRow(deps: AppDependencies, vm: SpaceViewModel, file: MaterialFile, detailed: Boolean, uploaded: Material?, courses: List<Material>, space: Space, nav: Navigator, library: LibraryViewModel) {
    val member = file.member
    val tag = memberTag(member)
    var menu by remember { mutableStateOf(false) }
    FileLine(file.kind, file.title, if (detailed) facts(file.kind, file.pages, file.chapters, null, file.addedAt) else "", null, A2g.skin.inkMuted, tag, { vm.viewing = member }) {
        Box {
            IconButton({ menu = !menu }, Modifier.size(48.dp).testTag("material_more_$tag")) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.material_more, file.title))
            }
            DropdownMenu(menu, { menu = false }) {
                val explain = if (uploaded?.tutorKey != null) listOf(uploaded to uploaded.tutorKey!!) else courses.mapNotNull { c -> c.tutorKey?.let { c to it } }
                explain.forEach { (material, key) ->
                    val local = material.localPack
                    // A course says how far the reader got, read fresh each time the menu opens.
                    val progress by produceState<TutorProgress?>(null, menu, local) { if (menu && local != null) value = runCatching { deps.tutors.progressFor(local.packageId, local.contentVersion) }.getOrNull() }
                    DropdownMenuItem({ Text((if (local != null) learnActionLabel(progress) else stringResource(R.string.material_explain)) + if (explain.size > 1) " · ${material.title}" else "") },
                        { menu = false; nav.openRead(key, sourcePackage = local?.packageId, version = local?.contentVersion) },
                        Modifier.testTag("start_explanation_${local?.packageId ?: key}"))
                }
                if (courses.isNotEmpty()) DropdownMenuItem({ Text(stringResource(if (member in vm.practising) R.string.material_practice_hide else R.string.material_practice)) },
                    { menu = false; vm.togglePractice(member) }, Modifier.testTag("practise_$tag"))
                if (uploaded?.tutorKey != null && courses.isEmpty()) DropdownMenuItem({ Text(stringResource(R.string.tutor_prepare_practice)) },
                    { menu = false; vm.perform { library.preparePractice(uploaded.tutorKey!!) } }, Modifier.testTag("prepare_practice_$tag"), enabled = !vm.busy)
                DropdownMenuItem({ Text(stringResource(R.string.spaces_remove)) }, { menu = false; vm.perform { vm.spaces.remove(space.spaceId, member) } },
                    Modifier.testTag("remove_$tag"), enabled = !vm.busy)
            }
        }
    }
}

/** A file that is not in the Space yet: still being prepared, or one that needs the reader's say-so. */
@Composable
private fun ImportRow(vm: SpaceViewModel, row: PdfImportItem) {
    var options by rememberSaveable(row.id) { mutableStateOf(false) }
    val unknown = row.error == PdfImportQueue.UPLOAD_OUTCOME_UNKNOWN
    val problem = row.error != null
    val skin = A2g.skin
    FileLine(FileKind.PDF, row.title, facts(FileKind.PDF, null, null, row.sizeBytes, row.addedAt),
        stringResource(when { unknown -> R.string.file_status_unsure; problem -> R.string.file_status_problem; else -> R.string.file_status_preparing }),
        if (problem) skin.wrong else skin.hint, row.id, { options = true }) {}
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text(row.title) },
        text = { Text(when { unknown -> stringResource(R.string.spaces_unknown); problem -> importErrorMessage(row.error!!); else -> stringResource(R.string.file_status_preparing) }) },
        confirmButton = { TextButton({ options = false; vm.retryImport(row, unknown) }, enabled = !vm.busy, modifier = Modifier.testTag("import_retry_${row.id}")) {
            Text(stringResource(if (unknown) R.string.spaces_upload_again else R.string.home_retry)) } },
        dismissButton = { TextButton({ options = false }) { Text(stringResource(R.string.import_cancel)) } })
}

/** The Space has no file yet: the character, one sentence, one button. */
@Composable
private fun EmptyMaterials(vm: SpaceViewModel, space: Space, pickPdfs: () -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("materials_empty"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(132.dp).background(A2g.skin.evidenceSoft, CircleShape), contentAlignment = Alignment.Center) {
            LocalAvatar.current.Draw(Mood.IDLE, Modifier.size(104.dp), pokeable = false)
        }
        Text(stringResource(R.string.files_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Button(pickPdfs, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pick_pdfs"), enabled = !vm.busy) { Text(stringResource(R.string.spaces_import)) }
        TextButton({ adding = true }, Modifier.heightIn(min = 48.dp).testTag("add_existing"), enabled = !vm.busy) { Text(stringResource(R.string.spaces_add)) }
    }
    if (adding) AddExistingDialog(vm, space) { adding = false }
}

/** A tapped file, full screen: its own PDF when the phone has it, its text (with a plain note) when not. */
@Composable
internal fun MaterialViewer(deps: AppDependencies, file: MaterialFile, onDismiss: () -> Unit) {
    OpenSourceViewer(deps.sources, SourceKey(file.source.contentId, file.source.contentVersion, file.course?.let { CourseRef(it.packageId, it.contentVersion) }),
        SourceText(file.title, file.chunks), onDismiss = onDismiss)
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
        Button(pickPdfs, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pick_pdfs"), enabled = !vm.busy) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.spaces_import))
        }
        OutlinedButton({ adding = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_existing"), enabled = !vm.busy) { Text(stringResource(R.string.spaces_add)) }
    }
    if (adding) AddExistingDialog(vm, space) { adding = false }
}

/** Files the reader already has on this phone (from other Spaces or courses) that this Space can take too. */
@Composable
private fun AddExistingDialog(vm: SpaceViewModel, space: Space, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.spaces_add)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            val available = vm.materials.distinctBy { it.member }.filterNot { it.member in space.members || it.localPack?.let(space.members::contains) == true }
            if (available.isEmpty()) Text(stringResource(R.string.spaces_no_existing))
            available.forEach { m -> TextButton({ close(); vm.perform { vm.spaces.add(space.spaceId, m.member) } }) { Text(m.title) } }
        } }, confirmButton = { TextButton(close) { Text(stringResource(R.string.action_back)) } })
}

/** The practice levels of one course, with the frame to play them in. */
@Composable
private fun PracticeList(deps: AppDependencies, material: Material, nav: Navigator, library: LibraryViewModel) {
    val local = material.localPack
    var entry by remember(material) { mutableStateOf<LibraryEntry?>(null) }
    var levels by remember(material) { mutableStateOf<List<PracticeLevel>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var explicit by rememberSaveable(local?.packageId, local?.contentVersion) { mutableStateOf<FrameId?>(null) }
    LaunchedEffect(material) {
        try {
            if (local != null) {
                val content = deps.content
                val version = ContentVersion(local.packageId, local.contentVersion)
                val course = requireNotNull(content.course(version))
                val pkg = course.pkg
                val manifest = course.manifest
                val frames = offeredFrames(pkg, content.available(version))
                val summary = PackageSummary(pkg.packageId, pkg.title, pkg.language, pkg.levels.size, manifest.origin == PackOrigin.PRESET)
                val saved = deps.sessions.load(local.packageId, local.contentVersion)
                val now = deps.clock.now()
                levels = practiceLevels(pkg, saved, deps.learningProgress.projection(), deps.learningProgress.profileId, now)
                entry = libraryEntry(summary, pkg, saved, deps.mastery.all(local.packageId, local.contentVersion), now).copy(
                    frames = frames, selectedFrame = deps.framePreferences.get(local.packageId)?.takeIf { it in frames } ?: FrameId.CLASSIC,
                    tutorProgress = deps.tutors.progressFor(local.packageId, local.contentVersion))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = problemCode(e) }
    }
    error?.let { Text(stringResource(R.string.spaces_error, problemText(it))) }
    entry?.let { e ->
        if (e.frames.size > 1) PlayAs(e.frames, explicit ?: e.selectedFrame) { frame -> explicit = frame; library.selectFrame(e.summary.packageId, frame) }
        levels.forEach { level ->
            HorizontalDivider()
            Text(level.title, style = MaterialTheme.typography.titleMedium)
            StatusBadge(level.status, Modifier.testTag("level_status_${e.summary.packageId}_${level.levelId}"))
            if (level.status.standing == Standing.DONE && !level.status.inProgress)
                Text(stringResource(R.string.status_done_hint), style = MaterialTheme.typography.bodySmall, color = A2g.skin.inkMuted)
            Button({ nav.openPlay(e.summary.packageId, level.levelId, explicit ?: e.frameFor(level.levelId), local?.contentVersion, explicit == null) },
                Modifier.fillMaxWidth().testTag("play_${e.summary.packageId}_${level.levelId}")) {
                Text(stringResource(if (level.status.inProgress) R.string.home_resume else R.string.home_practice_action))
            }
        }
    }
}
