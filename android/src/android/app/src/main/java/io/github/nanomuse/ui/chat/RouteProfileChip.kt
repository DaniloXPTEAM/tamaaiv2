package io.github.nanomuse.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.router.Profile

/** The override applies to the next user turn; the active chip shows what is actually sent. */
@Composable
fun RouteProfileChip(
    active: Profile?,
    override: Profile?,
    streaming: Boolean,
    onSelect: (Profile?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val shown = if (streaming) active else override ?: active
    val label = stringResource(when (shown) {
        Profile.LEAN -> R.string.nm_router_profile_lean
        Profile.BASIC -> R.string.nm_router_profile_basic
        Profile.FULL -> R.string.nm_router_profile_full
        null -> R.string.nm_router_profile_auto
    })
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            label = { Text(stringResource(R.string.nm_router_profile_chip, label)) },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val choices = listOf(
                null to R.string.nm_router_profile_auto,
                Profile.LEAN to R.string.nm_router_profile_lean,
                Profile.BASIC to R.string.nm_router_profile_basic,
                Profile.FULL to R.string.nm_router_profile_full,
            )
            choices.forEach { (profile, title) ->
                DropdownMenuItem(
                    text = { Text(stringResource(title) + if (profile == override) " ✓" else "") },
                    onClick = { onSelect(profile); open = false },
                )
            }
        }
    }
}
