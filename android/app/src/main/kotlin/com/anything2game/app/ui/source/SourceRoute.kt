package com.anything2game.app.ui.source

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.anything2game.app.R
import com.anything2game.app.data.source.CourseRef
import com.anything2game.app.data.source.SourceKey
import com.anything2game.app.data.source.SourceLibrary
import com.anything2game.app.data.source.SourceText
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.LearningPackage
import com.anything2game.engine.ports.PackageRepository

/** The route that opens one course's source (`Route.Source`): the same viewer as everywhere else, over a plain background. */
@Composable
internal fun SourceRoute(packages: PackageRepository, library: SourceLibrary, id: String, version: String, onBack: () -> Unit) {
    val result by produceState<Result<LearningPackage>?>(null, id, version) { value = packages.load(id, version) }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = A2g.skin.paper) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).testTag("source_route"), contentAlignment = Alignment.Center) {
            val pkg = result?.getOrNull()
            if (result?.isFailure == true) Text(stringResource(R.string.activity_missing))
            else if (pkg != null) OpenSourceViewer(library, SourceKey(pkg.documentId, pkg.documentVersion, CourseRef(pkg.packageId, pkg.contentVersion)),
                SourceText(pkg.title, pkg.sources), onDismiss = onBack)
        }
    }
}
