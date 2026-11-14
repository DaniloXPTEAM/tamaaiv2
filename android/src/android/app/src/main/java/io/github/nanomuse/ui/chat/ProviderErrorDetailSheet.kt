package io.github.nanomuse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.chat.StandardChatSheet
import io.github.nanomuse.net.ProviderReach
import io.github.nanomuse.ui.muse.setPlainText

private data class ProviderErrorDetail(
    val reach: ProviderReach.Reach,
    val guidance: String,
    val retry: () -> Unit,
)

/**
 * A sheet belongs to the chat rather than its lazy error row. It survives row recycling and
 * closes on session changes; the snapshot remains the actual failure even if the picker changes.
 */
@Composable
internal fun ProviderErrorDetailHost(
    sessionId: String,
    onChangeModel: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    content: @Composable (open: (ProviderReach.Reach, String, () -> Unit) -> Unit) -> Unit,
) {
    var detail by remember(sessionId) { mutableStateOf<ProviderErrorDetail?>(null) }
    content { reach, guidance, retry -> detail = ProviderErrorDetail(reach, guidance, retry) }
    detail?.let { selected ->
        ProviderErrorDetailSheet(
            reach = selected.reach,
            guidance = selected.guidance,
            onDismiss = { detail = null },
            onRetry = { detail = null; selected.retry() },
            onChangeModel = { detail = null; onChangeModel() },
            onOpenProviderSettings = { detail = null; onOpenProviderSettings() },
        )
    }
}

/** A bounded, redacted message is still potentially sensitive: copying is an explicit action. */
@Composable
internal fun ProviderErrorDetailSheet(
    reach: ProviderReach.Reach,
    guidance: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onChangeModel: () -> Unit,
    onOpenProviderSettings: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val unavailable = stringResource(R.string.nm_reach_sheet_unavailable)
    val providerLabel = stringResource(R.string.model_entry_provider)
    val modelLabel = stringResource(R.string.nm_chat_menu_model)
    val messageLabel = stringResource(R.string.nm_reach_sheet_message)
    val http = reach.httpStatus?.toString() ?: unavailable
    val provider = ProviderReach.redactBody(reach.host).ifBlank { unavailable }
    val model = ProviderReach.redactBody(reach.modelId).ifBlank { unavailable }
    // Legacy errors have no long message or model snapshot. Do not infer either from the
    // current model, and disclose that even redacted text may contain other sensitive data.
    val message = ProviderReach.redactBody(reach.originalMessage.ifBlank { reach.detail }).ifBlank { unavailable }
    val copy = "HTTP: $http\n$providerLabel: $provider\n$modelLabel: $model\n\n$messageLabel:\n$message"

    StandardChatSheet(title = stringResource(R.string.nm_reach_sheet_title), onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DetailField("HTTP", http)
            DetailField(providerLabel, provider)
            DetailField(modelLabel, model)
            if (guidance.isNotBlank()) DetailField(stringResource(R.string.nm_reach_helps), guidance)
            Text(messageLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(message, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.nm_reach_sheet_privacy), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { clipboard.setPlainText("TAMA AI", copy) }, modifier = Modifier.fillMaxWidth().testTag("provider-error-copy")) {
                Text(stringResource(R.string.common_copy))
            }
            TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("provider-error-retry")) {
                Text(stringResource(R.string.nm_reach_try_again))
            }
            TextButton(onClick = onChangeModel, modifier = Modifier.fillMaxWidth().testTag("provider-error-change-model")) {
                Text(stringResource(R.string.nm_reach_sheet_change_model))
            }
            TextButton(onClick = onOpenProviderSettings, modifier = Modifier.fillMaxWidth().testTag("provider-error-settings")) {
                Text(stringResource(R.string.nm_reach_sheet_provider_settings))
            }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
