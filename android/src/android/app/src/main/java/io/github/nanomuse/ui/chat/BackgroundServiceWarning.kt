package io.github.nanomuse.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.service.ServiceRequestIssue

/** Reports only an observed request failure, not whether Android will keep a service alive. */
@Composable
internal fun BackgroundServiceWarning(
    issue: ServiceRequestIssue,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (issue) {
        ServiceRequestIssue.START -> R.string.nm_background_start_rejected
        ServiceRequestIssue.STOP -> R.string.nm_background_stop_rejected
    }
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.nm_background_service_retry)) }
        }
    }
}
