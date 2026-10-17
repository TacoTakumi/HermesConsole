package com.hermesconsole.app.artifacts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * The image artifact's pane (REQ-16): the payload's `url` fetched, decoded and rendered at its
 * own aspect ratio, whole, in the landscape layout - never cropped, never stretched.
 *
 * The geometry is the acceptance made mechanical: the bitmap's intrinsic ratio is pinned with
 * [aspectRatio] and the pane is bounded by the content column's width and a height cap of
 * [MaxHeightFraction] of the screen, so the largest fit inside both bounds wins and the whole
 * image - aspect intact - sits centred and visible in one viewport alongside the reply, rather
 * than a width-filling portrait running taller than the wall. [ContentScale.Fit] holds the
 * no-clipping clause even if some future modifier disagrees with the box.
 *
 * The fetch is the applet's own - a URL read plus a two-pass [BitmapFactory] decode that
 * downsamples anything larger than [MaxDecodeEdge] before it becomes a wall-sized allocation.
 * Every failure - bad URL, dead network, undecodable bytes - lands in a readout line, never a
 * throw: no artifact is worth interrupting the spoken reply (REQ-15). A payload with no usable
 * `url` shows itself raw, the [MarkdownApplet] convention.
 */
object ImageApplet : ArtifactApplet {

    override val type = "image"

    private val _composed = MutableStateFlow(0)

    /**
     * How many bitmaps this applet has composed onto a wall - the connected tests' readiness
     * signal (T-25's verify): it moves only when a fetched image is actually in the tree, so a
     * screenshot taken after the bump photographs the rendering, not the RETRIEVING readout.
     */
    val composed: StateFlow<Int> = _composed.asStateFlow()

    @Composable
    override fun Render(artifact: Artifact) {
        val url = (artifact.args["url"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
        if (url == null) {
            BasicText(
                text = artifact.args.toString(),
                style = LcarsTheme.typography.body.copy(color = LcarsColors.OnSpace),
            )
            return
        }

        val load by produceState<Load>(Load.Fetching, url) {
            value = withContext(Dispatchers.IO) { fetch(url)?.let(Load::Ready) ?: Load.Failed }
        }
        when (val state = load) {
            Load.Fetching -> Readout("RETRIEVING IMAGE", LcarsColors.GoldenTanoi)
            Load.Failed -> Readout("IMAGE UNAVAILABLE - $url", LcarsColors.Mars)
            is Load.Ready -> {
                val bitmap = state.bitmap
                Box(Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                        contentDescription = url,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .sizeIn(maxHeight = (LocalConfiguration.current.screenHeightDp * MaxHeightFraction).dp)
                            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
                        contentScale = ContentScale.Fit,
                    )
                }
                LaunchedEffect(bitmap) { _composed.update { it + 1 } }
            }
        }
    }

    @Composable
    private fun Readout(text: String, colour: Color) {
        BasicText(text = text, style = LcarsTheme.typography.readout.copy(color = colour))
    }

    /**
     * The bytes at [url] as a decoded bitmap, or null for any reason at all - the caller renders
     * null as the readout, so nothing here may escape. file:// serves the on-device screenshot
     * rig the same way https:// serves a live run; both are just a URLConnection.
     */
    private fun fetch(url: String): Bitmap? = runCatching {
        val connection = URL(url).openConnection()
        connection.connectTimeout = ConnectTimeoutMs
        connection.readTimeout = ReadTimeoutMs
        (connection as? HttpURLConnection)?.instanceFollowRedirects = true
        val bytes = connection.getInputStream().use { it.readBytes() }
        decode(bytes)
    }.onFailure { Log.w(TAG, "image artifact fetch failed for $url: $it") }.getOrNull()

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }
                .first { maxOf(bounds.outWidth, bounds.outHeight) / it <= MaxDecodeEdge }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private sealed interface Load {
        data object Fetching : Load
        data object Failed : Load
        data class Ready(val bitmap: Bitmap) : Load
    }

    /**
     * The pane's height budget as a fraction of the screen. Measured on the 1920x1200 wall, the
     * header plus a query and a one-sentence reply leave about 0.41 of the screen's height above
     * the panel's bottom edge - 0.40 keeps the whole image in the first viewport beside a short
     * reply; a longer document above it scrolls, exactly as T-39 proved reachable.
     */
    private const val MaxHeightFraction = 0.40f

    /** Decoded pixels are capped near the panel's own resolution; more is allocation, not detail. */
    private const val MaxDecodeEdge = 2048

    private const val ConnectTimeoutMs = 10_000
    private const val ReadTimeoutMs = 30_000

    private const val TAG = "ImageApplet"
}
