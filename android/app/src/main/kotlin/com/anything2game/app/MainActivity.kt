package com.anything2game.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.activity.viewModels
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.importing.IntentImportParser
import com.anything2game.app.ui.AppRoot
import com.anything2game.app.ui.Navigator
import com.anything2game.app.ui.theme.A2gTheme

class MainActivity : ComponentActivity() {
    private val navigator: Navigator by viewModels()
    private val parser by lazy { IntentImportParser(contentResolver) }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deps = (application as A2gApplication).dependencies
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            A2gTheme {
                Box(Modifier.semantics { testTagsAsResourceId = true }) { AppRoot(deps, navigator) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        parser.parse(intent)?.let { request ->
            if (request is ImportRequest.PdfFile) runCatching {
                contentResolver.takePersistableUriPermission(request.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            navigator.openImport(request)
        }
    }
}
