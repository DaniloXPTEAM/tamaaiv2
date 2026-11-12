package io.github.nanomuse.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.ui.avatar.AgentAvatar
import io.github.nanomuse.ui.avatar.AgentMood
import io.github.nanomuse.ui.home.MuseRoundButton
import io.github.nanomuse.ui.home.MuseTones

/** The Pet's own page: the face at its current stage, the level, and what is left to the next one. */
const val ROUTE_PET = "tama/pet"

/**
 * The Pet screen.
 *
 * Deliberately thin: every number shown here comes from [PetProgress], which is pure and pinned by
 * tests; the face is the app's existing [AgentAvatar] rather than a second character renderer, so
 * the Pet and the chat header can never disagree about what the agent looks like. There is no
 * second scheduler here either — XP arrives from finished turns, and this screen only reads it.
 */
@Composable
fun PetScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // load() is idempotent and cheap; it makes the screen correct even when opened before any
    // turn has finished in this process.
    androidx.compose.runtime.LaunchedEffect(Unit) { PetStore.load(context) }
    val pet by PetStore.pet.collectAsState()

    Scaffold(containerColor = MuseTones.canvas) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp)) {
                MuseRoundButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.nm_back),
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            PetFace(mood = AgentMood.IDLE)
            Spacer(Modifier.height(18.dp))

            Text(
                text = stringResource(R.string.tama_pet_level, pet.level),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = pet.stage.label,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            LevelCard(pet)

            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.tama_pet_how_xp),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 44.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The face on its warm disc, reusing the chat header's avatar so there is one character only. */
@Composable
private fun PetFace(mood: AgentMood) {
    Box(
        modifier = Modifier
            .size(132.dp)
            .clip(CircleShape)
            .background(MuseTones.disc),
        contentAlignment = Alignment.Center,
    ) {
        AgentAvatar(
            mood = mood,
            size = 104.dp,
            contentDescription = stringResource(R.string.nm_avatar_content_description),
        )
    }
}

/** Level, XP total, and the ring towards the next level. */
@Composable
private fun LevelCard(pet: PetProgress) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MuseTones.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tama_pet_xp, pet.xp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pet.toNextLevel
                        ?.let { stringResource(R.string.tama_pet_to_next, it) }
                        ?: stringResource(R.string.tama_pet_max_level),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Drawn from the fraction rather than a progress widget: the determinate overload is
            // not used elsewhere in this module, so its signature is not verifiable from here.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MuseTones.fill),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pet.levelFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MuseTones.action),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The four stages, the reached ones in the action colour.
                for (stage in PetStage.entries) {
                    val reached = pet.stage.ordinal >= stage.ordinal
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (reached) MuseTones.action else MuseTones.hairline),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stage.label,
                        fontSize = 11.sp,
                        fontWeight = if (reached) FontWeight.Medium else FontWeight.Normal,
                        color = if (reached) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (stage != PetStage.entries.last()) Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}
