package com.muzrc.keluargamy360.ui.map

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.muzrc.keluargamy360.data.model.DecryptedMemberLocation
import com.muzrc.keluargamy360.util.RoadDistanceHelper
import com.muzrc.keluargamy360.util.RoadRouteEstimate
import kotlin.math.roundToInt

private const val OFFLINE_THRESHOLD_MS = 15L * 60L * 1000L // 15 minit

@Composable
fun MemberCard(
    member: DecryptedMemberLocation,
    isSelected: Boolean,
    myLocation: DecryptedMemberLocation? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val isOffline = member.updatedAt > 0L && (now - member.updatedAt) >= OFFLINE_THRESHOLD_MS
    val isMe = myLocation != null && myLocation.uid == member.uid

    val gridKey = remember(
        myLocation?.latitude,
        myLocation?.longitude,
        member.latitude,
        member.longitude,
        isMe
    ) {
        if (myLocation == null || isMe) {
            null
        } else {
            "${(myLocation.latitude * 1000).roundToInt()},${(myLocation.longitude * 1000).roundToInt()}->${(member.latitude * 1000).roundToInt()},${(member.longitude * 1000).roundToInt()}"
        }
    }

    var routeEstimate by remember(gridKey) {
        mutableStateOf<RoadRouteEstimate?>(
            if (myLocation == null || isMe) {
                null
            } else {
                RoadDistanceHelper.computeStraightLine(
                    myLocation.latitude,
                    myLocation.longitude,
                    member.latitude,
                    member.longitude
                )
            }
        )
    }

    LaunchedEffect(gridKey) {
        if (myLocation != null && !isMe && gridKey != null) {
            routeEstimate = RoadDistanceHelper.getRoadEstimate(
                myLat = myLocation.latitude,
                myLng = myLocation.longitude,
                targetLat = member.latitude,
                targetLng = member.longitude
            )
        }
    }

    val distanceLabel = routeEstimate?.formatCompact()

    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isOffline -> Color(0xFFFFA726).copy(alpha = 0.6f)
        else -> Color.Transparent
    }

    val batteryColor = when {
        isOffline -> MaterialTheme.colorScheme.onSurfaceVariant
        member.isCharging -> Color(0xFF4CAF50)
        member.batteryLevel <= 15 -> Color(0xFFEF5350)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (isSelected) 2.dp else if (isOffline) 1.dp else 0.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                if (member.photoUrl.isNotBlank()) {
                    AsyncImage(
                        model = member.photoUrl,
                        contentDescription = member.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .alpha(if (isOffline) 0.65f else 1f)
                            .clip(CircleShape)
                            .border(
                                2.dp,
                                if (isOffline) Color(0xFFFFA726) else MaterialTheme.colorScheme.primary,
                                CircleShape
                            )
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isOffline) Color(0xFF78909C) else MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = member.name.take(1).uppercase(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Titik status atas avatar (Oren jika luar talian > 15 minit, Hijau jika aktif)
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(if (isOffline) Color(0xFFFF9800) else Color(0xFF4CAF50))
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!isOffline && member.speedKmh >= 5f) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = "Bergerak",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = " ${member.speedKmh.roundToInt()} km/j",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = member.addressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isOffline) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = "Luar talian",
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val offlineText = buildString {
                            append("Luar talian • ${formatOfflineAge(member.updatedAt)}")
                            if (distanceLabel != null) append(" • $distanceLabel")
                        }
                        Text(
                            text = offlineText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFF9800),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    val statusSubtext = buildString {
                        if (isMe) {
                            append("Anda • ")
                        } else if (distanceLabel != null) {
                            append("$distanceLabel • ")
                        }
                        append(formatRelativeTime(member.updatedAt))
                    }
                    Text(
                        text = statusSubtext,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (distanceLabel != null && !isMe) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (distanceLabel != null && !isMe) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Status Bateri
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (member.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                    contentDescription = "Bateri",
                    tint = batteryColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "${member.batteryLevel}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = batteryColor,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Butang Navigasi Terus ke Lokasi Ahli (Waze / Google Maps)
            IconButton(
                onClick = {
                    val uri = Uri.parse(
                        "geo:${member.latitude},${member.longitude}?q=${member.latitude},${member.longitude}(${Uri.encode(member.name)})"
                    )
                    val navIntent = Intent(Intent.ACTION_VIEW, uri)
                    try {
                        context.startActivity(Intent.createChooser(navIntent, "Buka laluan ke ${member.name}"))
                    } catch (_: Exception) {
                        Toast.makeText(context, "Tiada aplikasi peta/navigasi dijumpai.", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = "Arah ke ${member.name}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diffSec = ((System.currentTimeMillis() - timestamp) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60 -> "Dikemas kini baru sebentar tadi"
        diffSec < 3600 -> "Dikemas kini ${diffSec / 60} minit lalu"
        diffSec < 86400 -> "Dikemas kini ${diffSec / 3600} jam lalu"
        else -> "Dikemas kini ${diffSec / 86400} hari lalu"
    }
}

private fun formatOfflineAge(timestamp: Long): String {
    val diffMin = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(1L)
    return when {
        diffMin < 60 -> "$diffMin min lalu"
        diffMin < 1440 -> "${diffMin / 60} jam lalu"
        else -> "${diffMin / 1440} hari lalu"
    }
}
