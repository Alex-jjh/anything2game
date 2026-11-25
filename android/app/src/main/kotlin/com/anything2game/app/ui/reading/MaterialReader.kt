package com.anything2game.app.ui.reading

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.home.PaperCard
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.SourceChunk
import com.anything2game.engine.ports.PackageRepository

internal fun LazyListScope.sourceChunks(chunks: List<SourceChunk>) {
    items(chunks) { chunk -> PaperCard {
        Text(stringResource(R.string.evidence_page, chunk.pageLabel ?: (chunk.pageIndex + 1).toString()), color = A2g.skin.evidence)
        Text(chunk.text, color = A2g.skin.ink)
    } }
}

@Composable
internal fun MaterialReader(packages: PackageRepository, id: String, version: String, onBack: () -> Unit) {
    val result by produceState<Result<LearningPackage>?>(null, id, version) { value = packages.load(id, version) }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = A2g.skin.paper) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("material_reader"), contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TextButton(onBack) { Text(stringResource(R.string.action_back)) } }
            when {
                result == null -> item { LinearProgressIndicator() }
                result?.isFailure == true -> item { Text(stringResource(R.string.activity_missing)) }
                else -> result?.getOrNull()?.let { pkg ->
                    item { Text(pkg.title, style = MaterialTheme.typography.headlineSmall) }
                    sourceChunks(pkg.sources)
                }
            }
        }
    }
}
