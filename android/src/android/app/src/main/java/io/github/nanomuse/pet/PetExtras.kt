package io.github.nanomuse.pet

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.ui.home.MuseTones
import java.util.UUID

private fun hoursCeiling(milliseconds: Long): Long =
    milliseconds.coerceAtLeast(0L).let { it / PetDecay.HOUR_MS + if (it % PetDecay.HOUR_MS == 0L) 0L else 1L }

@Composable
internal fun PetFeedingNotice(life: PetLife, now: Long) {
    val hours = hoursCeiling(PetLifeRules.untilDeath(life, now))
    Surface(shape = RoundedCornerShape(18.dp), color = MuseTones.surface, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.nm_pet_generation, life.generation), fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.nm_pet_feed_remaining, hours), fontSize = 13.sp,
                color = if (hours <= 24) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.nm_pet_feed_rule), fontSize = 12.sp, lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PetMemorial(life: PetLife, now: Long) {
    val end = PetLifeRules.griefEndsAt(life.deathAt ?: return)
    val remaining = if (now >= end) 0L else end - now.coerceAtLeast(0L)
    Image(
        painter = painterResource(R.drawable.tama_pet_ghost),
        contentDescription = stringResource(R.string.nm_pet_ghost),
        modifier = Modifier.size(150.dp).clip(CircleShape),
    )
    Spacer(Modifier.height(12.dp))
    Surface(
        shape = RoundedCornerShape(topStart = 65.dp, topEnd = 65.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
        color = MuseTones.surface,
        modifier = Modifier.width(210.dp).height(126.dp),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.nm_pet_in_memory), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(7.dp))
            Text(life.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
    Spacer(Modifier.height(18.dp))
    Text(
        stringResource(R.string.nm_pet_grief, hoursCeiling(remaining)),
        modifier = Modifier.padding(horizontal = 36.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

private fun PetMission.titleRes(): Int = when (this) {
    PetMission.FIRST_INTERACTION -> R.string.nm_pet_mission_first
    PetMission.CONVERSATION -> R.string.nm_pet_mission_conversation
    PetMission.TASK -> R.string.nm_pet_mission_task
    PetMission.GOAL -> R.string.nm_pet_mission_goal
    PetMission.LEARN -> R.string.nm_pet_mission_learn
    PetMission.CREATE -> R.string.nm_pet_mission_create
    PetMission.SKILL -> R.string.nm_pet_mission_skill
    PetMission.MILESTONE -> R.string.nm_pet_mission_milestone
    PetMission.MINIGAME -> R.string.nm_pet_mission_game
}

@Composable
internal fun PetMissionsCard(day: PetDaily) {
    PetCard {
        Text(stringResource(R.string.nm_pet_missions), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.nm_pet_daily_cap, day.earned), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, lineHeight = 17.sp)
        Text(stringResource(R.string.nm_pet_mission_sources), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(10.dp))
        for (mission in PetMission.entries) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(mission.titleRes()), Modifier.weight(1f), fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                val prefix = if (day.completed(mission)) "✓ " else ""
                Text(prefix + stringResource(R.string.nm_pet_mission_progress, day.count(mission), mission.target, mission.xp),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun PetActivity.titleRes(): Int = when (this) {
    PetActivity.DREAM -> R.string.nm_pet_activity_dream
    PetActivity.EXPLORE -> R.string.nm_pet_activity_explore
    PetActivity.EXPERIMENT -> R.string.nm_pet_activity_experiment
    PetActivity.JOURNAL -> R.string.nm_pet_activity_journal
}

@Composable
internal fun PetActivitiesCard(state: PetState) {
    val context = LocalContext.current
    var message by remember(state.life.generation) { mutableStateOf<Int?>(null) }
    PetCard {
        Text(stringResource(R.string.nm_pet_activities), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(stringResource(R.string.nm_pet_activities_local), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        for (row in PetActivity.entries.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (activity in row) {
                    SmallAction(
                        label = stringResource(R.string.nm_pet_activity_cost, stringResource(activity.titleRes()), activity.energyCost),
                        modifier = Modifier.weight(1f),
                    ) {
                        val done = PetStore.activity(context, activity, expectedGeneration = state.life.generation).performed
                        message = if (done) R.string.nm_pet_activity_done else R.string.nm_pet_low_energy
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(stringResource(R.string.nm_pet_curiosity, state.curiosity), fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.diary?.let { entry ->
            Text(stringResource(R.string.nm_pet_diary_entry, entry.level, entry.energy, entry.bond),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message?.let { Text(stringResource(it), fontSize = 12.sp, color = MuseTones.action) }
    }
}

@Composable
internal fun PetGamesCard(generation: Long) {
    var selected by remember(generation) { mutableStateOf<PetMiniGame?>(null) }
    var message by remember(generation) { mutableStateOf<Int?>(null) }
    PetCard {
        Text(stringResource(R.string.nm_pet_games), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(stringResource(R.string.nm_pet_games_rule), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(stringResource(R.string.nm_pet_game_colors), Modifier.weight(1f)) { selected = PetMiniGame.COLOR_ECHO }
            SmallAction(stringResource(R.string.nm_pet_game_numbers), Modifier.weight(1f)) { selected = PetMiniGame.STEP_PATTERN }
        }
        message?.let { Text(stringResource(it), fontSize = 12.sp, color = MuseTones.action, modifier = Modifier.padding(top = 8.dp)) }
    }
    selected?.let { game ->
        PetGameDialog(game, generation, onClose = { result -> selected = null; message = result })
    }
}

@Composable
private fun PetGameDialog(game: PetMiniGame, generation: Long, onClose: (Int?) -> Unit) {
    val context = LocalContext.current
    val session = remember(game, generation) { UUID.randomUUID().toString() }
    var reveal by remember(game, generation) { mutableStateOf(game == PetMiniGame.COLOR_ECHO) }
    var choices by remember(game, generation) { mutableStateOf<List<PetColor>>(emptyList()) }
    var wrong by remember(game, generation) { mutableStateOf(false) }

    fun submit(colors: List<PetColor>, number: Int?) {
        if (!PetMiniGames.won(game, colors, number)) {
            choices = emptyList()
            wrong = true
            return
        }
        val result = PetStore.finishGame(context, game, colors, number, session, generation)
        onClose(if (result.recorded) R.string.nm_pet_game_won else R.string.nm_pet_game_ended)
    }

    AlertDialog(
        onDismissRequest = { onClose(null) },
        title = { Text(stringResource(if (game == PetMiniGame.COLOR_ECHO) R.string.nm_pet_game_colors else R.string.nm_pet_game_numbers)) },
        text = {
            Column {
                if (game == PetMiniGame.COLOR_ECHO) {
                    if (reveal) {
                        Text(stringResource(R.string.nm_pet_color_sequence))
                    } else {
                        Text(stringResource(R.string.nm_pet_color_repeat, choices.size, PetMiniGames.colorSequence.size))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for (color in PetColor.entries) {
                                TextButton(onClick = {
                                    val next = choices + color
                                    wrong = false
                                    if (next.size == PetMiniGames.colorSequence.size) submit(next, null) else choices = next
                                }) { Text(stringResource(color.labelRes())) }
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.nm_pet_number_sequence))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (number in PetMiniGames.stepOptions) {
                            TextButton(onClick = { submit(emptyList(), number) }) { Text(number.toString()) }
                        }
                    }
                }
                if (wrong) Text(stringResource(R.string.nm_pet_game_retry), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        },
        confirmButton = {
            if (reveal) TextButton(onClick = { reveal = false }) { Text(stringResource(R.string.nm_pet_game_start)) }
        },
        dismissButton = { TextButton(onClick = { onClose(null) }) { Text(stringResource(R.string.nm_cancel)) } },
    )
}

private fun PetColor.labelRes(): Int = when (this) {
    PetColor.BLUE -> R.string.nm_pet_color_blue
    PetColor.YELLOW -> R.string.nm_pet_color_yellow
    PetColor.GREEN -> R.string.nm_pet_color_green
}

@Composable
internal fun PetArchiveCard(archives: List<PetArchive>) {
    if (archives.isEmpty()) return
    Spacer(Modifier.height(14.dp))
    PetCard {
        Text(stringResource(R.string.nm_pet_archives), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        for (archive in archives.asReversed()) {
            Text(stringResource(R.string.nm_pet_archive_entry, archive.life.name, archive.life.generation, archive.progress.xp),
                fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PetCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MuseTones.surface, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SmallAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MuseTones.fill,
        modifier = modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}
