package io.github.nanomuse.pet

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.avatar.AvatarStore
import io.github.nanomuse.avatar.OwnClip
import io.github.nanomuse.ui.avatar.AgentAvatar
import io.github.nanomuse.ui.avatar.AgentMood

internal fun PetStage.labelRes(): Int = when (this) {
    PetStage.AWAKENING -> R.string.nm_pet_stage_awakening
    PetStage.DISCOVERY -> R.string.nm_pet_stage_discovery
    PetStage.LEARNING -> R.string.nm_pet_stage_learning
    PetStage.COMPANION -> R.string.nm_pet_stage_companion
    PetStage.EXPLORER -> R.string.nm_pet_stage_explorer
    PetStage.EVOLVED -> R.string.nm_pet_stage_evolved
}

private fun PetStage.imageRes(): Int = when (this) {
    PetStage.AWAKENING -> R.drawable.nm_avatar_idle
    PetStage.DISCOVERY -> R.drawable.tama_pet_stage_discovery
    PetStage.LEARNING -> R.drawable.tama_pet_stage_learning
    PetStage.COMPANION -> R.drawable.tama_pet_stage_companion
    PetStage.EXPLORER -> R.drawable.tama_pet_stage_explorer
    PetStage.EVOLVED -> R.drawable.tama_pet_stage_evolved
}

/**
 * The built-in dragon grows into five new illustrations. A person's own picture, emoji or clip
 * is never replaced or uploaded: its stage gets a growing silhouette around the existing face.
 */
@Composable
internal fun PetStageLook(stage: PetStage, mood: AgentMood) {
    val custom by AvatarStore.current.collectAsState()
    val emoji by AvatarStore.emoji.collectAsState()
    val ownClip by OwnClip.current.collectAsState()
    val ownMoods by OwnClip.moods.collectAsState()
    val ownFace = custom != null || emoji != null || ownClip != null || ownMoods.isNotEmpty()
    if (!ownFace && stage != PetStage.AWAKENING) {
        // These stage illustrations are stills: a local motion gives feedback without generating clips.
        val motion = rememberInfiniteTransition(label = "petStageMotion")
        val breath by motion.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = if (mood == AgentMood.WORKING) 700 else 2600),
                RepeatMode.Reverse,
            ),
            label = "petStageBreath",
        )
        Image(
            painter = painterResource(stage.imageRes()),
            contentDescription = stringResource(R.string.tama_pet_face_description),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(104.dp).graphicsLayer {
                val scale = 1f + breath * if (mood == AgentMood.WORKING) .04f else .015f
                scaleX = scale
                scaleY = scale
                rotationZ = if (mood == AgentMood.WAITING) (breath - .5f) * 4f else 0f
            }.clip(CircleShape),
        )
    } else if (ownFace && stage != PetStage.AWAKENING) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            StageSilhouette(stage)
            AgentAvatar(mood = mood, size = 78.dp, contentDescription = stringResource(R.string.tama_pet_face_description))
        }
    } else {
        AgentAvatar(mood = mood, size = 104.dp, contentDescription = stringResource(R.string.tama_pet_face_description))
    }
}

/** Decorative wings and a crest, increasingly shaped at each stage, not a generated user image. */
@Composable
private fun StageSilhouette(stage: PetStage) {
    val tint = when (stage) {
        PetStage.AWAKENING -> Color(0xFFE9C671)
        PetStage.DISCOVERY -> Color(0xFFE9C671)
        PetStage.LEARNING -> Color(0xFFAF9CDA)
        PetStage.COMPANION -> Color(0xFF83B6A3)
        PetStage.EXPLORER -> Color(0xFF78B4CD)
        PetStage.EVOLVED -> Color(0xFFDCA75C)
    }
    Canvas(Modifier.size(112.dp)) {
        val w = size.width
        val h = size.height
        val level = stage.ordinal.toFloat()
        drawCircle(tint.copy(alpha = .22f), radius = w * (.38f + level * .012f))
        drawCircle(tint, radius = w * .405f, style = Stroke(width = w * .018f))
        if (stage.ordinal >= PetStage.LEARNING.ordinal) {
            val lift = .10f + level * .018f
            for (side in listOf(-1f, 1f)) {
                val cx = w * .5f
                val wing = Path().apply {
                    moveTo(cx + side * w * .30f, h * .40f)
                    quadraticTo(cx + side * w * .50f, h * (0.30f - lift), cx + side * w * .49f, h * .62f)
                    quadraticTo(cx + side * w * .42f, h * .56f, cx + side * w * .32f, h * .70f)
                    close()
                }
                drawPath(wing, tint.copy(alpha = .7f))
            }
        }
        if (stage.ordinal >= PetStage.COMPANION.ordinal) {
            val peaks = if (stage == PetStage.COMPANION) 2 else if (stage == PetStage.EXPLORER) 3 else 5
            repeat(peaks) { index ->
                val x = w * (.5f + (index - (peaks - 1) / 2f) * .07f)
                drawCircle(tint, radius = w * (.018f + stage.ordinal * .002f), center = Offset(x, h * .10f))
            }
        }
    }
}
