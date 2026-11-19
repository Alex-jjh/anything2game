package com.anything2game.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.data.profile.LocalProfile
import com.anything2game.app.ui.character.Avatars
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.theme.A2g

/**
 * First screen: who is playing. The sample student opens a lived-in app (courses played, one review due, one case
 * half-way) — ideal for a demo; the guest starts fresh. Both are local profiles; sign-in can be added as a third.
 */
@Composable
fun WelcomeScreen(onChoose: (LocalProfile) -> Unit) {
    val skin = A2g.skin
    Column(
        Modifier.fillMaxSize().background(skin.paper).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("welcome"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = skin.ink)
        Text(stringResource(R.string.library_subtitle), style = MaterialTheme.typography.bodyLarge, color = skin.inkMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Avatars.all.forEach { it.Draw(Mood.IDLE, Modifier.size(84.dp)) }
        }
        PaperCard {
            Text(stringResource(R.string.profile_sample_title), style = MaterialTheme.typography.titleLarge, color = skin.ink)
            Text(stringResource(R.string.profile_sample_body), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
            Button(onClick = { onChoose(LocalProfile.SAMPLE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("choose_sample")) {
                Text(stringResource(R.string.profile_sample_action))
            }
        }
        PaperCard {
            Text(stringResource(R.string.profile_guest_title), style = MaterialTheme.typography.titleLarge, color = skin.ink)
            Text(stringResource(R.string.profile_guest_body), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
            OutlinedButton(onClick = { onChoose(LocalProfile.GUEST) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("choose_guest")) {
                Text(stringResource(R.string.profile_guest_action))
            }
        }
        Text(stringResource(R.string.profile_local_note), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
    }
}
