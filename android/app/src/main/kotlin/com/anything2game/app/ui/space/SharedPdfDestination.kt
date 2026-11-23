package com.anything2game.app.ui.space

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g

@Composable
internal fun SharedPdfDestination(deps: AppDependencies, language: String, title: String, onChoose: (String) -> Unit, onBack: () -> Unit) {
    val vm = spaceModel(deps, language)
    var creating by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = A2g.skin.paper) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("share_spaces"), contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TextButton(onBack) { Text(stringResource(R.string.action_back)) } }
            item { Text(stringResource(R.string.share_space, title), style = MaterialTheme.typography.headlineSmall) }
            item { OutlinedButton({ creating = true }, enabled = !vm.busy) { Text(stringResource(R.string.spaces_new)) } }
            if (creating) item { NameEditor("", { creating = false }) { name ->
                vm.perform { val space = vm.spaces.create(name); onChoose(space.spaceId) }
            } }
            if (vm.busy) item { LinearProgressIndicator() }
            vm.error?.let { item { Text(stringResource(R.string.spaces_error, it)); TextButton({ vm.perform() }) { Text(stringResource(R.string.home_retry)) } } }
            items(vm.cards, key = { it.space.spaceId }) { card ->
                OutlinedButton({ onChoose(card.space.spaceId) }, Modifier.fillMaxWidth()) { Text(card.space.title) }
            }
        }
    }
}
