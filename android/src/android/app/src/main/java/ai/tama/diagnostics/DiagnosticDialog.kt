package ai.tama.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.ui.muse.setPlainText

/** Preview first. Only a tap on Copy writes the closed-schema report to the clipboard. */
@Composable
internal fun DiagnosticDialog(snapshot: DiagnosticReport.Snapshot, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val keyboard = LocalSoftwareKeyboardController.current
    var copied by remember { mutableStateOf<Boolean?>(null) }
    val report = DiagnosticReport.format(snapshot)
    LaunchedEffect(Unit) { keyboard?.hide() }

    AlertDialog(
        modifier = Modifier.testTag("tama-diagnostic-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nm_diagnostic_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.nm_diagnostic_privacy))
                SelectionContainer {
                    Text(report, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("tama-diagnostic-report"))
                }
                copied?.let {
                    Text(stringResource(if (it) R.string.nm_diagnostic_copied else R.string.nm_diagnostic_copy_failed))
                }
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("tama-diagnostic-copy"),
                onClick = {
                    copied = try {
                        clipboard.setPlainText("TAMA diagnostic", report)
                        true
                    } catch (_: Exception) {
                        false
                    }
                },
            ) { Text(stringResource(R.string.nm_diagnostic_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
