package io.github.nanomuse.ui.muse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.ui.home.MuseTones

/**
 * The TAMA mark as a hero, on a warm rounded square. For the screens
 * that are about the app and the account — sign-in, the first-run door — where the face would
 * promise a conversation that is not there yet.
 */
@Composable
fun NmBrandMark(size: Dp = 104.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(MuseTones.action),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_stat_tama),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}
