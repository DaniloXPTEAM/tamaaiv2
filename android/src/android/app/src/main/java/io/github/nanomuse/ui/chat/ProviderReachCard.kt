package io.github.nanomuse.ui.chat

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.chat.ChatLinkResolver
import io.github.nanomuse.cloud.OwnKeyPresets
import io.github.nanomuse.cloud.ProviderCatalogue
import io.github.nanomuse.net.ProviderReach
import io.github.nanomuse.ui.home.MuseTones

/** The in-app link that opens Settings → Network (the proxy for own providers). */
const val NETWORK_DEEP_LINK = "minis://settings/network"

/**
 * The failed turn's error, as the chat shows it: when the text is a provider that could not
 * be reached — DNS, connect, TLS, a timeout, an interception page — or OpenAI refusing the
 * region, the sign-in having run out or the plan having nothing left, a card that says what
 * happened and what helps ([ProviderReachCard]); every other error keeps upstream's [banner].
 * [onRetryOnCloud], when given (signed in, and the turn ran on a model of the person's own),
 * adds *Use nanoMuse Cloud this time* under either: one turn on the relay, no slot changed.
 */
@Composable
fun NmProviderErrorOrBanner(
    error: String,
    onRetry: () -> Unit,
    onRetryOnCloud: (() -> Unit)? = null,
    onOpenDetails: ((ProviderReach.Reach, String) -> Unit)? = null,
    banner: @Composable () -> Unit,
) {
    // the relay's refusals (413 too large, busy, the operator's switches …): one sentence and a button
    val refusal = remember(error) { io.github.nanomuse.cloud.RelayRefusal.fromCanonical(error) }
    if (refusal != null) {
        RelayRefusalCard(refusal, onRetry)
        return
    }
    val reach = remember(error) { ProviderReach.classify(error) }
    if (reach != null) {
        ProviderReachCard(reach, onRetry, onRetryOnCloud = onRetryOnCloud,
            onOpenDetails = onOpenDetails?.let { open -> { guidance -> open(reach, guidance) } })
        return
    }
    Column {
        banner()
        if (onRetryOnCloud != null) CloudThisTimeButton(onRetryOnCloud, modifier = Modifier.padding(top = 2.dp))
    }
}

/** *Use nanoMuse Cloud this time*: the text button the rejection cards share. */
@Composable
fun CloudThisTimeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Outlined.CloudQueue, contentDescription = null, modifier = Modifier.size(15.dp), tint = MuseTones.accent)
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.nm_retry_on_cloud), fontSize = 13.sp, color = MuseTones.accent)
    }
}

/**
 * What happened, in one sentence a person can act on; what helps, as a short list; the
 * actions: *Try again*, the Network setting, *Sign in again* or a key of one's own. When a
 * stable chat host is provided, the card and its Details button open the same detail sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderReachCard(
    reach: ProviderReach.Reach,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryOnCloud: (() -> Unit)? = null,
    onOpenDetails: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val host = reach.host.ifBlank { stringResource(R.string.nm_reach_the_provider) }
    val plan = reach.isChatGptPlan

    val (icon, tint, title, body) = when (reach.kind) {
        ProviderReach.Kind.UNREACHABLE -> Head(
            Icons.Outlined.CloudOff, Color(0xFFE0772C),
            stringResource(R.string.nm_reach_unreachable_title, host),
            stringResource(R.string.nm_reach_unreachable_body, host),
        )
        ProviderReach.Kind.REGION_BLOCKED -> Head(
            Icons.Outlined.Public, Color(0xFFE0772C),
            stringResource(R.string.nm_reach_region_title),
            stringResource(R.string.nm_reach_region_body),
        )
        ProviderReach.Kind.SIGNED_OUT -> Head(
            Icons.Outlined.VpnKey, MuseTones.accent,
            stringResource(if (plan) R.string.nm_reach_signed_out_title else R.string.nm_reach_key_refused_title, host),
            stringResource(if (plan) R.string.nm_reach_signed_out_body else R.string.nm_reach_key_refused_body),
        )
        ProviderReach.Kind.QUOTA -> Head(
            Icons.Outlined.HourglassEmpty, Color(0xFF7C5CFF),
            stringResource(if (plan) R.string.nm_reach_quota_title else R.string.nm_reach_quota_key_title, host),
            stringResource(if (plan) R.string.nm_reach_quota_body else R.string.nm_reach_quota_key_body),
        )
        ProviderReach.Kind.NO_CREDIT -> Head(
            Icons.Outlined.AccountBalanceWallet, Color(0xFFE0772C),
            stringResource(R.string.nm_reach_no_credit_title, host),
            stringResource(R.string.nm_reach_no_credit_body),
        )
        ProviderReach.Kind.AUTH_REJECTED -> Head(
            Icons.Outlined.VpnKey, Color(0xFFE0772C),
            stringResource(R.string.nm_reach_auth_title, host),
            stringResource(R.string.nm_reach_auth_body),
        )
        ProviderReach.Kind.ACCESS_DENIED -> Head(
            Icons.Outlined.Key, Color(0xFFE0772C),
            stringResource(R.string.nm_reach_access_title, host),
            stringResource(R.string.nm_reach_access_body),
        )
        ProviderReach.Kind.RATE_LIMITED -> Head(
            Icons.Outlined.HourglassEmpty, Color(0xFF7C5CFF),
            stringResource(R.string.nm_reach_rate_title),
            stringResource(R.string.nm_reach_rate_body),
        )
        ProviderReach.Kind.REQUEST_TOO_LARGE -> Head(
            Icons.Outlined.HourglassEmpty, Color(0xFFE0772C),
            stringResource(R.string.nm_reach_request_large_title, host),
            stringResource(R.string.nm_reach_request_large_body),
        )
        ProviderReach.Kind.RESPONSE_LIMIT -> Head(
            Icons.Outlined.HourglassEmpty, Color(0xFFE0772C),
            stringResource(R.string.nm_reach_output_limit_title, host),
            if (reach.outputLimit != null) stringResource(R.string.nm_reach_output_limit_known, reach.outputLimit)
            else stringResource(R.string.nm_reach_output_limit_unknown),
        )
        ProviderReach.Kind.SERVER_ERROR -> Head(
            Icons.Outlined.CloudOff, Color(0xFFE0772C),
            stringResource(R.string.nm_reach_server_error_title, host),
            stringResource(R.string.nm_reach_server_error_body),
        )
        ProviderReach.Kind.PROVIDER_ERROR -> Head(
            Icons.Outlined.CloudOff, Color(0xFFE0772C),
            stringResource(R.string.nm_reach_generic_title, host),
            stringResource(R.string.nm_reach_generic_body),
        )
    }

    // nanoMuse: the whole card is a discoverable target; nested action buttons keep their own clicks.
    val detailsLabel = stringResource(R.string.nm_reach_details)
    val detailsTarget = if (onOpenDetails != null) {
        Modifier.testTag("provider-error-card").clickable(onClickLabel = detailsLabel) { onOpenDetails(body) }
    } else Modifier
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MuseTones.surface,
        border = BorderStroke(1.dp, MuseTones.hairline),
        modifier = modifier.fillMaxWidth().widthIn(max = 400.dp).padding(top = 4.dp).then(detailsTarget),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                    // The failed request, not the current picker, supplies the model. Old or
                    // transport-only errors do not invent one when no snapshot exists.
                    Text(
                        "${stringResource(R.string.model_entry_provider)}: $host · " +
                            "${stringResource(R.string.nm_chat_menu_model)}: " +
                            reach.modelId.ifBlank { stringResource(R.string.nm_reach_sheet_unavailable) },
                        fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Text(body, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    if (reach.detail.isNotBlank() && reach.kind != ProviderReach.Kind.UNREACHABLE) {
                        Text(
                            stringResource(R.string.nm_reach_vendor_said, reach.detail),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    reach.retryAfterS?.takeIf { it > 0 }?.let { s ->
                        Text(
                            stringResource(R.string.nm_reach_resets_in, duration(context, s)),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // what helps
            val helps = when (reach.kind) {
                ProviderReach.Kind.UNREACHABLE, ProviderReach.Kind.REGION_BLOCKED -> listOf(
                    R.string.nm_reach_help_vpn,
                    R.string.nm_reach_help_proxy,
                    R.string.nm_reach_help_other,
                )
                ProviderReach.Kind.QUOTA -> listOf(R.string.nm_reach_help_wait, R.string.nm_reach_help_other)
                ProviderReach.Kind.RATE_LIMITED -> listOf(R.string.nm_reach_help_moment)
                ProviderReach.Kind.SIGNED_OUT, ProviderReach.Kind.AUTH_REJECTED,
                ProviderReach.Kind.ACCESS_DENIED, ProviderReach.Kind.REQUEST_TOO_LARGE,
                ProviderReach.Kind.RESPONSE_LIMIT, ProviderReach.Kind.SERVER_ERROR,
                ProviderReach.Kind.PROVIDER_ERROR -> emptyList()
                ProviderReach.Kind.NO_CREDIT -> listOf(R.string.nm_reach_help_credit, R.string.nm_reach_help_free_model)
            }
            if (helps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.nm_reach_helps), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                helps.forEach { res ->
                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.Top) {
                        Text("·", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(res), fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // the actions
            FlowRow(verticalArrangement = Arrangement.Center, modifier = Modifier.padding(top = 10.dp)) {
                when (reach.kind) {
                    ProviderReach.Kind.SIGNED_OUT, ProviderReach.Kind.AUTH_REJECTED,
                    ProviderReach.Kind.ACCESS_DENIED, ProviderReach.Kind.NO_CREDIT -> {
                        val signIn = reach.kind == ProviderReach.Kind.SIGNED_OUT && plan
                        Button(
                            onClick = { if (signIn) signInAgain(context) else ChatLinkResolver.dispatchDeepLink(context, "minis://settings/providers") },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = Color.White),
                        ) {
                            Text(stringResource(if (signIn) R.string.nm_reach_sign_in else R.string.nm_reach_open_provider), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.nm_reach_try_again), fontSize = 13.sp, color = MuseTones.accent) }
                    }
                    else -> {
                        Button(
                            onClick = onRetry,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = Color.White),
                        ) {
                            Text(stringResource(R.string.nm_reach_try_again), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.width(6.dp))
                        if (reach.kind == ProviderReach.Kind.UNREACHABLE || reach.kind == ProviderReach.Kind.REGION_BLOCKED) {
                            TextButton(onClick = { ChatLinkResolver.dispatchDeepLink(context, NETWORK_DEEP_LINK) }) {
                                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(15.dp), tint = MuseTones.accent)
                                Spacer(Modifier.width(5.dp))
                                Text(stringResource(R.string.nm_reach_network), fontSize = 13.sp, color = MuseTones.accent)
                            }
                        }
                        if (reach.kind == ProviderReach.Kind.UNREACHABLE || reach.kind == ProviderReach.Kind.REGION_BLOCKED ||
                            reach.kind == ProviderReach.Kind.QUOTA) {
                            TextButton(onClick = { ChatLinkResolver.dispatchDeepLink(context, OwnKeyPresets.deepLink(firstKeyVendor(context))) }) {
                                Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(15.dp), tint = MuseTones.accent)
                                Spacer(Modifier.width(5.dp))
                                Text(stringResource(R.string.nm_reach_own_key), fontSize = 13.sp, color = MuseTones.accent)
                            }
                        }
                    }
                }
                // one turn on the relay, the slots untouched (0.1.41): never automatic
                if (onRetryOnCloud != null) CloudThisTimeButton(onRetryOnCloud)
            }

            // nanoMuse: an explicit label remains available for accessibility and discovery.
            if (onOpenDetails != null) {
                TextButton(onClick = { onOpenDetails(body) }) { Text(detailsLabel, fontSize = 12.sp) }
            }
        }
    }
}

private data class Head(val icon: ImageVector, val tint: Color, val title: String, val body: String)

/** The provider form on the ChatGPT sign-in, through the catalogue's vendor that offers it. */
private fun signInAgain(context: Context) {
    val vendor = ProviderCatalogue.load(context).firstOrNull { it.signIn == ProviderCatalogue.AUTH_CHATGPT }
    val link = if (vendor != null) OwnKeyPresets.deepLink(vendor.id, signIn = true) else "minis://settings/providers"
    ChatLinkResolver.dispatchDeepLink(context, link)
}

/** The region's first key vendor, the one the allowance card leads with. */
private fun firstKeyVendor(context: Context): String {
    val mainland = io.github.nanomuse.cloud.Region.mainland(context)
    return ProviderCatalogue.ordered(ProviderCatalogue.load(context), mainland).firstOrNull()?.id ?: OwnKeyPresets.BAILIAN
}

/** "2 h 05 min" / "40 min", for a Retry-After. */
fun duration(context: Context, seconds: Int): String {
    val minutes = (seconds.toLong() + 59L) / 60L
    val h = minutes / 60L
    val m = minutes % 60L
    return if (h > 0L) context.getString(R.string.nm_reach_hours_minutes, h, m) else context.getString(R.string.nm_reach_minutes, maxOf(m, 1L))
}
