package com.forja.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.forja.app.core.data.Friend
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.Body
import com.forja.app.core.designsystem.Positive
import com.forja.app.core.designsystem.SleepRem
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface2
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.components.pressable

/** Amberul locurilor/selecției — același cu inelul pinului (definit în PlacesSheet.kt ca `PlaceAmber`). */

/**
 * Banda cu prieteni deasupra barei de jos (ca în Bump): avataruri de 40 dp cu punct de stare;
 * atingere → camera zboară la prieten și se deschide cardul. Cei fără poziție apar estompați.
 */
@Composable
fun FriendsStrip(
    friends: List<Friend>,
    selectedUid: String?,
    onPick: (Friend) -> Unit,
    modifier: Modifier = Modifier
) {
    if (friends.isEmpty()) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
    ) {
        items(friends, key = { it.uid }) { f ->
            val onMap = f.lat != null && f.lng != null && (!f.ghost || f.viaFamily)
            Box(Modifier.pressable({ onPick(f) })) {
                FriendAvatar(friend = f, size = 40.dp, selected = f.uid == selectedUid, dim = !onMap)
            }
        }
    }
}

/** Avatar rotund cu fotografie (dacă există) sau inițiale, inel după stare, punct de stare. */
@Composable
fun FriendAvatar(friend: Friend, size: Dp, selected: Boolean = false, dim: Boolean = false) {
    val now = System.currentTimeMillis()
    val moving = friend.state == "run" || friend.state == "walk" || friend.state == "ride"
    val fresh = now - friend.locUpdatedAt < 15 * 60_000L
    val ring = when {
        selected -> PlaceAmber
        friend.viaFamily || friend.ghost -> SleepRem
        friend.state == "sleep" -> SleepRem
        fresh -> Accent2
        else -> TextDim
    }
    val initials = friend.name.trim().split(Regex("\\s+")).take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("").ifEmpty { "?" }
    Box(Modifier.size(size + 4.dp).alpha(if (dim) 0.45f else 1f)) {
        Box(
            Modifier
                .size(size)
                .align(Alignment.Center)
                .border(if (selected) 2.5.dp else 2.dp, ring, CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
                .background(Surface2),
            contentAlignment = Alignment.Center
        ) {
            if (!friend.photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = friend.photoUrl,
                    contentDescription = friend.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size).clip(CircleShape)
                )
            } else {
                Text(
                    initials,
                    style = Body.copy(color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = (size.value / 2.8f).sp)
                )
            }
        }
        if (!friend.ghost && (moving || friend.state == "sleep")) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 1.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Surface0)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (friend.state == "sleep") SleepRem else Positive)
            )
        }
        if (friend.viaFamily) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(SleepRem),
                contentAlignment = Alignment.Center
            ) {
                Text("♥", style = Body.copy(color = Color(0xFF0A0A0B), fontSize = 8.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}
