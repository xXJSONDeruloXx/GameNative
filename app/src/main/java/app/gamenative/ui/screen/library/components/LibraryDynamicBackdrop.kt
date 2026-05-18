package app.gamenative.ui.screen.library.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.ui.enums.PaneType
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The carousel backdrop only needs to read as a soft, blurred image. Render it into a
// smaller layer, blur that smaller surface, then scale it back up so we keep the same look
// without paying full-screen blur cost every frame.
private const val DYNAMIC_BACKDROP_RENDER_SCALE = 0.72f
private const val DYNAMIC_BACKDROP_IMAGE_SCALE = 1.06f
private val DYNAMIC_BACKDROP_BLUR_RADIUS = 12.dp
private val DYNAMIC_BACKDROP_RENDER_BLUR_RADIUS = DYNAMIC_BACKDROP_BLUR_RADIUS * DYNAMIC_BACKDROP_RENDER_SCALE

@Composable
internal fun LibraryDynamicBackdrop(
    appInfo: LibraryItem?,
    imageRefreshCounter: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Crossfade(
            targetState = appInfo,
            animationSpec = tween(durationMillis = 500),
            label = "backdrop_fade",
        ) { targetInfo ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (targetInfo != null) {
                    val imageUrls by produceState(
                        initialValue = GridImageUrls("", ""),
                        key1 = targetInfo.appId,
                        key2 = imageRefreshCounter,
                    ) {
                        value = withContext(Dispatchers.IO) {
                            getGridImageUrl(context, targetInfo, PaneType.GRID_HERO)
                        }
                    }

                    var currentImageUrl by remember(
                        imageUrls.primary,
                        imageUrls.fallback,
                        targetInfo.appId,
                        imageRefreshCounter,
                    ) {
                        mutableStateOf(imageUrls.primary.ifEmpty { imageUrls.fallback })
                    }

                    if (currentImageUrl.isNotEmpty()) {
                        CoilImage(
                            modifier = Modifier
                                .fillMaxSize(DYNAMIC_BACKDROP_RENDER_SCALE)
                                .blur(DYNAMIC_BACKDROP_RENDER_BLUR_RADIUS)
                                .graphicsLayer {
                                    val scale = DYNAMIC_BACKDROP_IMAGE_SCALE / DYNAMIC_BACKDROP_RENDER_SCALE
                                    scaleX = scale
                                    scaleY = scale
                                },
                            imageModel = { currentImageUrl },
                            imageOptions = ImageOptions(
                                contentScale = ContentScale.Crop,
                                contentDescription = null,
                            ),
                            loading = {},
                            failure = {
                                if (imageUrls.fallback.isNotEmpty() && currentImageUrl == imageUrls.primary) {
                                    currentImageUrl = imageUrls.fallback
                                }
                            },
                            previewPlaceholder = painterResource(R.drawable.ic_logo_color),
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f)),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.74f),
                            0.16f to Color.Black.copy(alpha = 0.52f),
                            0.38f to Color.Black.copy(alpha = 0.24f),
                            0.62f to Color.Black.copy(alpha = 0.34f),
                            1.0f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.34f),
                            0.14f to Color.Black.copy(alpha = 0.16f),
                            0.5f to Color.Transparent,
                            0.86f to Color.Black.copy(alpha = 0.16f),
                            1.0f to Color.Black.copy(alpha = 0.34f),
                        ),
                    ),
                ),
        )
    }
}
