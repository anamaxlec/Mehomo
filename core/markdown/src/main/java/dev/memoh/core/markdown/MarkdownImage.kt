package dev.memoh.core.markdown

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.memoh.core.designsystem.component.MemohSkeleton
import dev.memoh.core.designsystem.component.MemohSkeletonBlock

/** The containing screen supplies its account-aware loader. */
val LocalMarkdownImageLoader = staticCompositionLocalOf<(suspend (String) -> ByteArray)?> { null }

private sealed interface ImageState {
    data object Loading : ImageState
    data class Ready(val bitmap: ImageBitmap) : ImageState
    data class Failed(val message: String) : ImageState
}

@Composable
fun MarkdownImage(destination: String, alt: String, modifier: Modifier = Modifier) {
    val loader = LocalMarkdownImageLoader.current
    var retry by remember(destination) { mutableIntStateOf(0) }
    var preview by remember(destination) { mutableStateOf(false) }
    val state by produceState<ImageState>(ImageState.Loading, destination, loader, retry) {
        value = ImageState.Loading
        try {
            val bytes = requireNotNull(loader) { "图片加载不可用" }.invoke(destination)
            value = withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                while (bounds.outWidth / options.inSampleSize > 2048 || bounds.outHeight / options.inSampleSize > 2048) {
                    options.inSampleSize *= 2
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: error("暂不支持这种图片格式")
                ImageState.Ready(bitmap.asImageBitmap())
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { value = ImageState.Failed(e.message ?: "图片加载失败") }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val current = state) {
            is ImageState.Ready -> Image(
                bitmap = current.bitmap,
                contentDescription = alt.ifBlank { "图片" },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    .aspectRatio(current.bitmap.width.toFloat() / current.bitmap.height)
                    .clip(MaterialTheme.shapes.medium).clickable { preview = true },
            )
            ImageState.Loading -> MemohSkeleton(description = "正在加载图片") {
                MemohSkeletonBlock(Modifier.fillMaxWidth().height(160.dp), MaterialTheme.shapes.medium)
            }
            is ImageState.Failed -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(current.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { retry++ }) { Text("重试") }
            }
        }
        if (alt.isNotBlank()) Text(alt, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val bitmap = (state as? ImageState.Ready)?.bitmap
    if (preview && bitmap != null) Dialog(onDismissRequest = { preview = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Image(bitmap, alt, Modifier.fillMaxSize().transformable(transform)
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit)
            IconButton(onClick = { preview = false }, Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Filled.Close, "关闭图片", tint = Color.White)
            }
        }
    }
}
