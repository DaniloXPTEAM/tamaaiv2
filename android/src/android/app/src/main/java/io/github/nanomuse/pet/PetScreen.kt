package io.github.nanomuse.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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

/** The Pet's own page: the face, its mood, its needs and its level. */
const val ROUTE_PET = "tama/pet"

/**
 * The Pet screen.
 *
 * Every number shown here comes from [PetState], which is pure and pinned by tests; the face is
 * the app's existing [AgentAvatar] rather than a second character renderer, so the Pet and the
 * chat header can never disagree about what the agent looks like. There is no second scheduler
 * here either — the needs decay is recovered from two timestamps on read, so nothing runs while
 * the app is closed.
 */
@Composable
fun PetScreen(
    onBack: () -> Unit,
    /** Whether a turn is running right now, so the Pet looks up while the work happens. */
    streaming: Boolean = false,
    /** The current error, when one is showing, so a failure becomes a wince. */
    error: String? = null,
) {
    val context = LocalContext.current
    // Idempotent and cheap: it makes the screen correct even when opened in a cold process.
    LaunchedEffect(Unit) { PetStore.load(context) }
    val state by PetStore.state.collectAsState()
    // The live seam (2.3): the face follows the action, then settles back into its needs.
    val presence = rememberPetPresence(streaming = streaming, error = error)
    val mood = PetLive.mood(presence = presence, needs = state.needs, aloneHours = state.aloneHours)

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
            PetFace(mood = mood, outfit = state.outfit)
            Spacer(Modifier.height(14.dp))

            // What the Pet is feeling, in words, so the face is not the only carrier.
            Text(
                text = stringResource(state.mood.labelRes()),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            // What it remembers: the run of days, and how long the two of them have known each
            // other. A streak of zero is simply not shown rather than shown as a failure.
            val today = PetStreak.dayOf(System.currentTimeMillis())
            val streakDays = PetStreak.shownOn(state.streak, today)
            Text(
                text = buildString {
                    if (streakDays > 0) {
                        append(
                            pluralStringResource(
                                R.plurals.tama_pet_streak,
                                streakDays,
                                streakDays,
                            ),
                        )
                        append("  \u00b7  ")
                    }
                    append(stringResource(R.string.tama_pet_days_known, state.memory.daysKnown(today)))
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            NeedsCard(state.needs)

            Spacer(Modifier.height(14.dp))
            CareRow(onCare = { action ->
                PetStore.care(context, action)
            })

            Spacer(Modifier.height(14.dp))
            WardrobeRow(
                state = state,
                onPick = { outfit -> PetStore.wear(context, outfit) },
            )

            Spacer(Modifier.height(14.dp))
            LevelCard(state.progress)

            Spacer(Modifier.height(26.dp))
            Text(
                text = stringResource(R.string.tama_pet_how_xp),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** The text the Pet's mood shows. Kept out of the enum so the pure core has no resources. */
private fun PetMood.labelRes(): Int = when (this) {
    PetMood.CONTENT -> R.string.tama_pet_mood_content
    PetMood.CALM -> R.string.tama_pet_mood_calm
    PetMood.NEEDY -> R.string.tama_pet_mood_needy
    PetMood.FOCUSED -> R.string.tama_pet_mood_focused
    PetMood.GRUMPY -> R.string.tama_pet_mood_grumpy
}

/** The five expressions the app already draws, chosen by the mood. */
private fun PetMood.agentMood(): AgentMood = when (asset) {
    PetMoodAsset.IDLE -> AgentMood.IDLE
    PetMoodAsset.WORKING -> AgentMood.WORKING
    PetMoodAsset.WAITING -> AgentMood.WAITING
    PetMoodAsset.HAPPY -> AgentMood.HAPPY
    PetMoodAsset.ERROR -> AgentMood.ERROR
}

/**
 * The face in its scene (SL-001).
 *
 * The outfit is a backdrop behind the disc and one emoji at its corner — never a second character
 * renderer, so whatever face the person chose for the agent (the dragon, a preset, an emoji) is
 * still the face inside the costume.
 */
@Composable
private fun PetFace(mood: PetMood, outfit: PetOutfit) {
    Box(
        modifier = Modifier
            .size(132.dp)
            .clip(CircleShape)
            .background(if (outfit.backdrop.paints) outfit.backdrop.color() else MuseTones.disc),
        contentAlignment = Alignment.Center,
    ) {
        AgentAvatar(
            mood = mood.agentMood(),
            size = 104.dp,
            contentDescription = stringResource(R.string.tama_pet_face_description),
        )
        outfit.accessory?.let { accessory ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MuseTones.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = accessory, fontSize = 15.sp)
            }
        }
    }
}

/** The scene colours, taken from the app's own tones so dark mode stays coherent. */
@Composable
private fun PetBackdrop.color(): Color = when (this) {
    PetBackdrop.NONE -> MuseTones.disc
    PetBackdrop.DAWN -> if (MuseTones.isDark) Color(0xFF2A2540) else Color(0xFFF3EFFB)
    PetBackdrop.MOSS -> if (MuseTones.isDark) Color(0xFF1F2A21) else Color(0xFFEDF3EA)
    PetBackdrop.DUSK -> if (MuseTones.isDark) Color(0xFF22222E) else Color(0xFFEDEEF4)
    PetBackdrop.TIDE -> if (MuseTones.isDark) Color(0xFF1C2830) else Color(0xFFE8F1F4)
}

/** The four needs, each a labelled bar from 0 to 100. */
@Composable
private fun NeedsCard(needs: PetNeeds) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MuseTones.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            for (need in PetNeed.entries) {
                NeedRow(need, needs[need])
                if (need != PetNeed.entries.last()) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun NeedRow(need: PetNeed, value: Int) {
    val low = value < PetNeeds.NEEDY_THRESHOLD
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(need.labelRes()),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(92.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(CircleShape)
                .background(MuseTones.fill),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.coerceIn(0, 100) / 100f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (low) MaterialTheme.colorScheme.error else MuseTones.action),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$value",
            fontSize = 12.sp,
            fontWeight = if (low) FontWeight.SemiBold else FontWeight.Normal,
            color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(26.dp),
        )
    }
}

private fun PetNeed.labelRes(): Int = when (this) {
    PetNeed.SATIETY -> R.string.tama_pet_need_satiety
    PetNeed.ENERGY -> R.string.tama_pet_need_energy
    PetNeed.MOOD -> R.string.tama_pet_need_mood
    PetNeed.BOND -> R.string.tama_pet_need_bond
}

/** Feed, rest, play — the three things the person can do for the Pet. */
@Composable
private fun CareRow(onCare: (PetCareAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CareButton(stringResource(R.string.tama_pet_action_feed), Modifier.weight(1f)) {
            onCare(PetCareAction.FEED)
        }
        CareButton(stringResource(R.string.tama_pet_action_rest), Modifier.weight(1f)) {
            onCare(PetCareAction.REST)
        }
        CareButton(stringResource(R.string.tama_pet_action_play), Modifier.weight(1f)) {
            onCare(PetCareAction.PLAY)
        }
    }
}

@Composable
private fun CareButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MuseTones.surface,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The outfits, in one horizontally scrolling strip (SL-001).
 *
 * A locked one is shown greyed with the level it needs rather than hidden: a reward the person can
 * see is a reward. The one being worn is marked, and the one merely *chosen* while still locked is
 * marked differently, so the choice is never silently lost.
 */
@Composable
private fun WardrobeRow(state: PetState, onPick: (PetOutfit) -> Unit) {
    val level = state.progress.level
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.tama_pet_wardrobe),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (outfit in PetOutfit.ALL) {
                val unlocked = PetOutfit.isUnlocked(outfit, level)
                val worn = state.outfit.id == outfit.id
                val chosen = state.wardrobe.chosenId == outfit.id
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (worn) MuseTones.action else MuseTones.surface,
                    modifier = Modifier
                        .size(width = 74.dp, height = 64.dp)
                        .clickable(enabled = unlocked) { onPick(outfit) },
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = outfit.accessory ?: "—",
                            fontSize = 17.sp,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (unlocked) {
                                stringResource(R.string.tama_pet_outfit_name, outfit.id)
                            } else {
                                stringResource(R.string.tama_pet_outfit_locked, outfit.unlockLevel)
                            },
                            fontSize = 10.sp,
                            maxLines = 1,
                            color = when {
                                worn -> Color.White
                                unlocked -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
                            },
                        )
                        if (chosen && !worn) {
                            Box(
                                Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(MuseTones.action),
                            )
                        }
                    }
                }
            }
        }
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
                    text = stringResource(R.string.tama_pet_level, pet.level),
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
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.tama_pet_xp, pet.xp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
