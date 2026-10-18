package com.hermesconsole.app.devicetools

import android.util.Log
import com.hermesconsole.hermesclient.DeviceTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

private const val TAG = "DeviceTools"

/** A volume stream in Android's own steps, 0..[max]: the media stream on the tablet, a fake in tests. */
interface MediaVolume {
    var index: Int
    val max: Int
}

/**
 * The volume tools: get_volume reads both levels in percent, and set_volume sets one of them.
 *
 * media is Android's media stream, which other apps play on. It has few steps (15 on the wall
 * tablet), so a percent lands on the nearest step and the reply reports that step back in percent.
 * voice is the console's own level, for its speech, announcements and cues. It has a floor of
 * [VOICE_FLOOR_PERCENT]: at 0 the console would answer in silence, and a user who cannot hear it
 * has no spoken way back up.
 *
 * A call with bad arguments throws, which the device link sends back as a tool error, and nothing
 * changes.
 */
fun volumeTools(
    media: MediaVolume,
    voicePercent: () -> Int,
    setVoicePercent: suspend (Int) -> Unit,
): List<DeviceTool> {
    fun mediaPercent(): Int = if (media.max <= 0) 0 else (media.index * 100.0 / media.max).roundToInt()

    val getVolume = DeviceTool(
        name = "get_volume",
        description = "Read the console tablet's two volume levels in percent: media (other apps such as " +
            "YouTube and Spotify) and voice (the console's own speech and sounds).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
    ) {
        val reply = buildJsonObject {
            put("media", mediaPercent())
            put("voice", voicePercent())
        }
        Log.i(TAG, "get_volume -> $reply")
        reply.toString()
    }

    val setVolume = DeviceTool(
        name = "set_volume",
        description = "Set one of the console tablet's volume levels. Give exactly one of percent or direction. " +
            "direction moves the level 10 points. The reply is the level the target has afterwards.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("target") {
                    put("type", "string")
                    put("enum", buildJsonArray { add(JsonPrimitive(MEDIA)); add(JsonPrimitive(VOICE)) })
                    put("description", "media for other apps such as YouTube and Spotify, voice for the console's own speech and sounds.")
                }
                putJsonObject("percent") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", 100)
                    put("description", "The level to set, 0 to 100.")
                }
                putJsonObject("direction") {
                    put("type", "string")
                    put("enum", buildJsonArray { add(JsonPrimitive(UP)); add(JsonPrimitive(DOWN)) })
                    put("description", "Turn the level up or down by 10 points.")
                }
            }
            put("required", buildJsonArray { add(JsonPrimitive("target")) })
        },
    ) { args ->
        val target = args.stringArg("target")
        require(target == MEDIA || target == VOICE) { "target must be '$MEDIA' or '$VOICE', not '$target'" }
        val percent = args["percent"]?.let { element ->
            val value = runCatching { element.jsonPrimitive.doubleOrNull }.getOrNull()
                ?: throw IllegalArgumentException("percent must be a number from 0 to 100")
            value.roundToInt().also { require(it in 0..100) { "percent must be from 0 to 100, not $it" } }
        }
        val direction = args["direction"]?.let { args.stringArg("direction") }
        require((percent == null) != (direction == null)) { "give exactly one of percent or direction" }
        require(direction == null || direction == UP || direction == DOWN) {
            "direction must be '$UP' or '$DOWN', not '$direction'"
        }

        fun wanted(current: Int): Int = percent ?: (current + if (direction == UP) STEP else -STEP).coerceIn(0, 100)

        val reply = if (target == MEDIA) {
            media.index = (wanted(mediaPercent()) * media.max / 100.0).roundToInt()
            buildJsonObject {
                put("target", MEDIA)
                put("percent", mediaPercent())
            }
        } else {
            val asked = wanted(voicePercent())
            val level = asked.coerceAtLeast(VOICE_FLOOR_PERCENT)
            setVoicePercent(level)
            buildJsonObject {
                put("target", VOICE)
                put("percent", level)
                if (level != asked) {
                    put("note", "The console's voice does not go below $VOICE_FLOOR_PERCENT percent, so it was set to $VOICE_FLOOR_PERCENT.")
                }
            }
        }
        Log.i(TAG, "set_volume $target ${percent ?: direction} -> $reply")
        reply.toString()
    }

    return listOf(getVolume, setVolume)
}

private const val MEDIA = "media"
private const val VOICE = "voice"
private const val UP = "up"
private const val DOWN = "down"

/** Points a direction moves either level. */
private const val STEP = 10

/** The lowest voice level: low enough to be quiet, high enough to hear the console say how to turn it up. */
const val VOICE_FLOOR_PERCENT = 10

private fun JsonObject.stringArg(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()
