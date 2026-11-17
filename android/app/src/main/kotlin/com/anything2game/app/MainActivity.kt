package com.anything2game.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.anything2game.app.importing.IntentImportParser
import com.anything2game.app.ui.AppRoot
import com.anything2game.app.ui.Navigator
import com.anything2game.app.ui.theme.A2gTheme

class MainActivity : ComponentActivity() {
    private val navigator: Navigator by viewModels()
    private val parser by lazy { IntentImportParser(contentResolver) }

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { navigator.openImport(parser.pdf(it)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deps = (application as A2gApplication).dependencies
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            A2gTheme {
                AppRoot(deps, navigator, onPickPdf = { pickPdf.launch(arrayOf(IntentImportParser.PDF)) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        parser.parse(intent)?.let(navigator::openImport)
    }
}
