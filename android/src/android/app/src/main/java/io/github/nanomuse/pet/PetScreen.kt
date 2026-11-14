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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.R
import com.openminis.app.service.SessionActivityTracker
import io.github.nanomuse.ui.avatar.AgentMood
import io.github.nanomuse.ui.home.MuseRoundButton
import io.github.nanomuse.ui.home.MuseTones
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The Pet's own page: the face, its mood, its needs and its level. */
const val ROUTE_PET = "tama/pet"

/**
 * The Pet screen.
 *
 * Needs, rewards and life are reconciled from local state. The built-in face has stage artwork;
 * a person's own face stays theirs and receives a stage silhouette without any image upload.
 * There is no background scheduler: elapsed time is reconciled when the app is next used.
 */
@Composable
fun PetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A screen can remain composed while the phone is backgrounded. Only a resumed Pet screen
    // counts as visible, and returning from the background must reconcile the absence again.
    DisposableEffect(lifecycle, context) {
        fun updateVisibility() {
            PetStore.setVisible(context, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycle.addObserver(observer)
        updateVisibility()
        onDispose {
            lifecycle.removeObserver(observer)
            PetStore.setVisible(context, false)
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // A screen left open must reach the feeding and grief boundaries as well as local midnight.
    LaunchedEffect(lifecycle, context) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                now = System.currentTimeMillis()
                PetStore.load(context, now)
                delay(60_000L)
            }
        }
    }
    val today = PetStreak.dayOf(now)
    val state by PetStore.state.collectAsState()
    val activeSessions by SessionActivityTracker.activeSessions.collectAsState()
    val presence = rememberPetPresence(streaming = activeSessions.isNotEmpty())
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
            if (state.life.grieving) {
                // The memorial is read-only: no care, wardrobe, activities, games or missions.
                PetMemorial(state.life, now)
            } else {
                PetFace(mood = mood, outfit = state.outfit, stage = state.progress.stage)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(mood.labelRes()),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                val streakDays = PetStreak.shownOn(state.streak, today)
                Text(
                    text = buildString {
                        if (streakDays > 0) {
                            append(pluralStringResource(R.plurals.tama_pet_streak, streakDays, streakDays))
                            append("  \u00b7  ")
                        }
                        append(stringResource(R.string.tama_pet_days_known, state.memory.daysKnown(today)))
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                PetFeedingNotice(state.life, now)
                Spacer(Modifier.height(14.dp))
                NeedsCard(state.needs)
                Spacer(Modifier.height(14.dp))
                CareRow(onCare = { action -> PetStore.care(context, action, expectedGeneration = state.life.generation) })
                Spacer(Modifier.height(14.dp))
                WardrobeRow(state = state, onPick = { outfit ->
                    PetStore.wear(context, outfit, expectedGeneration = state.life.generation)
                })
                Spacer(Modifier.height(14.dp))
                LevelCard(state.progress)
                Spacer(Modifier.height(14.dp))
                PetMissionsCard(state.daily)
                Spacer(Modifier.height(14.dp))
                PetActivitiesCard(state)
                Spacer(Modifier.height(14.dp))
                PetGamesCard(state.life.generation)
                PetArchiveCard(state.archives)
                Spacer(Modifier.height(26.dp))
                Text(
                    text = stringResource(R.string.nm_pet_xp_rules),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
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

/** The stage look and the chosen outfit share one scene. */
@Composable
private fun PetFace(mood: PetMood, outfit: PetOutfit, stage: PetStage) {
    Box(
        modifier = Modifier
            .size(132.dp)
            .clip(CircleShape)
            .background(if (outfit.backdrop.paints) outfit.backdrop.color() else MuseTones.disc),
        contentAlignment = Alignment.Center,
    ) {
        PetStageLook(stage = stage, mood = mood.agentMood())
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
                    .background(if (low) MaterialTheme.colorScheme.error else MuseTones.accent),
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
        modifier = modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
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
                        .clickable(role = Role.Button) { onPick(outfit) },
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
                        .background(MuseTones.accent),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (stage in PetStage.entries) {
                    val reached = pet.stage.ordinal >= stage.ordinal
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (reached) MuseTones.accent else MuseTones.hairline),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(stage.labelRes()),
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
