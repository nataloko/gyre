package dev.paperouette.wallpaper.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import dev.paperouette.wallpaper.data.ImageSource
import dev.paperouette.wallpaper.data.decoderSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A piece of catalogued artwork, decoded no larger than the space it is being drawn into.
 *
 * The catalogue's previews are far bigger than any tile, so the decode is sized against the
 * measured bounds rather than the source. [source] carries which store the bytes come from, so
 * bundled and imported artwork draw through the same composable.
 */
@Composable
fun AssetImage(source: ImageSource, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    val bitmap by produceState<Bitmap?>(null, source, measuredSize) {
        value = null
        if (measuredSize.width <= 0 || measuredSize.height <= 0) return@produceState
        value = withContext(Dispatchers.IO) {
            // A missing or corrupt asset keeps the loading-color placeholder instead of crashing.
            runCatching {
                ImageDecoder.decodeBitmap(source.decoderSource(context)) { decoder, info, _ ->
                    val sourceWidth = info.size.width
                    val sourceHeight = info.size.height
                    val scale = max(
                        measuredSize.width.toFloat() / sourceWidth,
                        measuredSize.height.toFloat() / sourceHeight,
                    ).coerceAtMost(1f)
                    decoder.setTargetSize(
                        max(1, (sourceWidth * scale).roundToInt()),
                        max(1, (sourceHeight * scale).roundToInt()),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.onSizeChanged { size -> measuredSize = size },
            contentScale = ContentScale.Crop,
        )
    } ?: Box(modifier.onSizeChanged { size -> measuredSize = size })
}
