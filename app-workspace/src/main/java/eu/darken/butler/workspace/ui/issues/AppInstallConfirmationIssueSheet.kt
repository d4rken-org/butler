package eu.darken.butler.workspace.ui.issues

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.InstallMobile
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.pkgs.installer.AppInstallConfirmationIssue
import eu.darken.butler.common.io.R as IoR

/**
 * Offers Android's install confirmation again. The system shows that dialog only for an app in the
 * foreground, so an install started and then backgrounded needs this way back to it.
 */
@Composable
fun AppInstallConfirmationIssueSheet(
    issue: AppInstallConfirmationIssue,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = issue.title.asComposable(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        Text(
            modifier = Modifier.padding(bottom = 8.dp),
            text = issue.description.asComposable(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                try {
                    context.startActivity(issue.confirmIntent)
                } catch (e: Exception) {
                    log(ERROR) { "Failed to re-open the install confirmation: ${e.asLog()}" }
                }
                onConfirmed()
            },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.TwoTone.InstallMobile,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(text = stringResource(IoR.string.app_install_confirm_pending_action))
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppInstallConfirmationIssueSheetPreview() {
    AppInstallConfirmationIssueSheet(
        issue = AppInstallConfirmationIssue(label = "Example App", confirmIntent = Intent()),
        onConfirmed = {},
    )
}
