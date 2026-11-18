package ai.tama.providers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ai.tama.pricing.FreeModelImport
import ai.tama.pricing.FreeModelImportStatus
import com.openminis.app.R
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.ui.settings.SettingsCardBlock
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import io.github.nanomuse.cloud.OwnKeyPresets
import io.github.nanomuse.cloud.ProviderCatalogue
import io.github.nanomuse.cloud.Region

/** Explicit vendor selection, so a compatible protocol never guesses a host from a key. */
@Composable
internal fun KnownProviderSection(onSelect: (VoiceProviderTemplate) -> Unit) {
    val context = LocalContext.current
    val mainland = remember { Region.mainland(context) }
    val chinese = remember { ProviderCatalogue.chinese(context) }
    val providers = remember {
        ProviderCatalogue.ordered(ProviderCatalogue.load(context), mainland).filter { it.takesKey }
    }
    val featured = remember(providers) {
        listOf("openrouter", "groq", "gemini", "openai", "anthropic")
            .mapNotNull { id -> providers.firstOrNull { it.id == id } }
    }
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) featured + providers.filter { it !in featured } else featured
    if (shown.isEmpty()) return
    SettingsSection(
        header = stringResource(R.string.nm_provider_known_title),
        footer = stringResource(R.string.nm_provider_known_help),
    ) {
        shown.forEachIndexed { index, provider ->
            val template = OwnKeyPresets.template(provider, mainland, chinese)
            SettingsRow(
                title = template.name,
                subtitle = ProviderEndpoints.effective(template.providerType, template.baseURL, template.appendV1),
                onClick = { onSelect(template) },
                showDivider = !showAll || index != shown.lastIndex,
            )
        }
        if (!showAll && providers.size > shown.size) {
            SettingsRow(
                title = stringResource(R.string.nm_provider_show_all),
                onClick = { showAll = true },
                showDivider = false,
            )
        }
    }
}

/** Suggest locally. Prefixes are hints, not proof of an account or recipient. */
internal fun suggestedKeyProvider(context: android.content.Context, apiKey: String): VoiceProviderTemplate? {
    val providers = ProviderCatalogue.load(context).filter { it.takesKey }
    val matchId = ProviderKeySuggestion.match(apiKey, providers.map { it.id to it.keyHint }) ?: return null
    val provider = providers.firstOrNull { it.id == matchId } ?: return null
    return OwnKeyPresets.template(provider, Region.mainland(context), ProviderCatalogue.chinese(context))
}

/** An inferred endpoint is displayed immediately, but cannot be saved without this tap. */
@Composable
internal fun KeyProviderConfirmation(providerName: String, onConfirm: () -> Unit) {
    SettingsSection(footer = stringResource(R.string.nm_key_autofill_help)) {
        SettingsRow(
            title = stringResource(R.string.nm_key_autofill_confirm, providerName),
            onClick = onConfirm,
            showDivider = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EndpointRegionSection(mainland: Boolean, onSelect: (Boolean) -> Unit) {
    SettingsSection(
        header = stringResource(R.string.nm_endpoint_region),
        footer = stringResource(R.string.nm_endpoint_region_help),
    ) {
        SettingsCardBlock {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !mainland,
                    onClick = { onSelect(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.nm_endpoint_global)) }
                SegmentedButton(
                    selected = mainland,
                    onClick = { onSelect(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.nm_endpoint_mainland)) }
            }
        }
    }
}

@Composable
internal fun freeModelImportStatusText(status: FreeModelImportStatus?): String? = when (status) {
    FreeModelImportStatus.Checking -> stringResource(R.string.nm_free_import_checking)
    FreeModelImportStatus.MissingKey -> stringResource(R.string.nm_free_import_missing_key)
    FreeModelImportStatus.Failed -> stringResource(R.string.nm_free_import_failed)
    FreeModelImportStatus.SaveFailed -> stringResource(R.string.nm_provider_save_failed)
    is FreeModelImportStatus.Complete -> if (status.eligible == 0) {
        stringResource(R.string.nm_free_import_empty)
    } else {
        stringResource(R.string.nm_free_import_found, status.eligible, status.added)
    }
    FreeModelImportStatus.Unsupported -> stringResource(R.string.nm_free_import_unsupported)
    null -> null
}

@Composable
internal fun FreeModelImportSection(
    type: ProviderType,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    status: FreeModelImportStatus? = null,
) {
    val unsupported = checked && !FreeModelImport.supports(type)
    val statusText = freeModelImportStatusText(status).takeUnless { unsupported && status == FreeModelImportStatus.Unsupported }
    val help = stringResource(R.string.nm_free_import_help)
    val explanation = if (unsupported) "$help\n${stringResource(R.string.nm_free_import_unsupported)}" else help
    SettingsSection(footer = listOfNotNull(explanation, statusText?.takeIf { checked || status == FreeModelImportStatus.SaveFailed }).joinToString("\n")) {
        SettingsSwitchRow(
            title = stringResource(R.string.nm_free_import_title),
            checked = checked,
            onCheckedChange = onCheckedChange,
            showDivider = false,
        )
    }
}

@Composable
internal fun FixedEndpointSection(endpoint: String) {
    SettingsSection(
        header = stringResource(R.string.add_provider_endpoint),
        footer = stringResource(R.string.nm_endpoint_fixed_help),
    ) {
        SettingsRow(
            title = stringResource(R.string.nm_endpoint_effective, endpoint),
            showChevron = false,
            showDivider = false,
        )
    }
}
