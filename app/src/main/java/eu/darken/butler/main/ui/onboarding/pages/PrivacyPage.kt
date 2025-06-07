package eu.darken.butler.main.ui.onboarding.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Preview2
@Composable
private fun PrivacyPagePreview() {
    PreviewWrapper {
        PrivacyPage(
            onAccept = {},
        )
    }
}

@Composable
internal fun PrivacyPage(onAccept: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Privacy & Data",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Your privacy is important to us. Butler processes data locally on your device and respects your privacy preferences.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = {
                uriHandler.openUri("https://github.com/d4rken-org/butler/blob/main/PRIVACY_POLICY.md")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Read Privacy Policy")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Accept & Continue")
        }
    }
}