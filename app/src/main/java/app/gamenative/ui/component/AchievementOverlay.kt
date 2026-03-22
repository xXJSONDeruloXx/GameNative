package app.gamenative.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.AchievementNotification
import app.gamenative.ui.util.AchievementNotificationManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val AchievementOverlayMargin = 16.dp

@Composable
fun BoxScope.AchievementOverlay() {
    var current by remember { mutableStateOf<AchievementNotification?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AchievementNotificationManager.notifications.collect { notification ->
            current = notification
            visible = true
            delay(4000)
            visible = false
            delay(500)
            current = null
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val marginPx = with(density) { AchievementOverlayMargin.roundToPx() }.toFloat()
        val containerWidthPx = with(density) { maxWidth.roundToPx() }
        val containerHeightPx = with(density) { maxHeight.roundToPx() }

        var notificationSize by remember { mutableStateOf(IntSize.Zero) }
        var notificationOffset by remember { mutableStateOf<Offset?>(null) }

        fun maxX(): Float = (containerWidthPx - notificationSize.width).coerceAtLeast(0).toFloat()

        fun maxY(): Float = (containerHeightPx - notificationSize.height).coerceAtLeast(0).toFloat()

        fun defaultOffset(): Offset = Offset(
            x = (maxX() - marginPx).coerceAtLeast(0f),
            y = (maxY() - marginPx).coerceAtLeast(0f),
        )

        fun persistOffset(offset: Offset) {
            val maxOffsetX = maxX()
            val maxOffsetY = maxY()
            PrefManager.achievementOverlayXFraction = if (maxOffsetX > 0f) {
                offset.x / maxOffsetX
            } else {
                0f
            }
            PrefManager.achievementOverlayYFraction = if (maxOffsetY > 0f) {
                offset.y / maxOffsetY
            } else {
                0f
            }
        }

        fun restoreOffset() {
            if (containerWidthPx <= 0 || containerHeightPx <= 0 || notificationSize == IntSize.Zero) {
                return
            }

            val maxOffsetX = maxX()
            val maxOffsetY = maxY()
            val defaultOffset = defaultOffset()
            val savedX = PrefManager.achievementOverlayXFraction
            val savedY = PrefManager.achievementOverlayYFraction

            notificationOffset = Offset(
                x = if (savedX in 0f..1f) maxOffsetX * savedX else defaultOffset.x,
                y = if (savedY in 0f..1f) maxOffsetY * savedY else defaultOffset.y,
            )
        }

        LaunchedEffect(containerWidthPx, containerHeightPx, notificationSize) {
            restoreOffset()
        }

        val anchoredOffset = notificationOffset ?: defaultOffset()
        val translation = anchoredOffset - defaultOffset()

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(AchievementOverlayMargin)
                .offset {
                    IntOffset(
                        x = translation.x.roundToInt(),
                        y = translation.y.roundToInt(),
                    )
                }
                .onSizeChanged { notificationSize = it }
                .pointerInput(containerWidthPx, containerHeightPx, notificationSize) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val currentOffset = notificationOffset ?: defaultOffset()
                            notificationOffset = Offset(
                                x = (currentOffset.x + dragAmount.x).coerceIn(0f, maxX()),
                                y = (currentOffset.y + dragAmount.y).coerceIn(0f, maxY()),
                            )
                        },
                        onDragEnd = {
                            notificationOffset?.let(::persistOffset)
                        },
                        onDragCancel = {
                            notificationOffset?.let(::persistOffset)
                        },
                    )
                },
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            current?.let { notification ->
                AchievementNotificationContent(notification)
            }
        }
    }
}

@Composable
private fun AchievementNotificationContent(notification: AchievementNotification) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (notification.iconUrl != null) {
                CoilImage(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    imageModel = { notification.iconUrl },
                    imageOptions = ImageOptions(
                        contentScale = ContentScale.Crop,
                    ),
                    previewPlaceholder = painterResource(R.drawable.ic_logo_color),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column {
                Text(
                    text = stringResource(R.string.achievement_unlocked),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = notification.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_AchievementOverlay() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            AchievementNotificationContent(
                AchievementNotification(
                    name = "Real boy : They all lived happily ever after",
                    iconUrl = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/12345/abc.jpg",
                )
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview_AchievementOverlay_NoIcon() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            AchievementNotificationContent(
                AchievementNotification(
                    name = "First Blood",
                    iconUrl = null,
                )
            )
        }
    }
}
