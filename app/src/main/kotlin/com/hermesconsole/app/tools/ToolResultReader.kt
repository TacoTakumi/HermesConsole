package com.hermesconsole.app.tools

import com.hermesconsole.hermesclient.parseJsonContainer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tool result, read into something a human can read at a glance and expand (REQ-04..06, REQ-08).
 *
 * [summary] is one line for the collapsed strip; [body] is the bounded, human-readable text the row
 * expands to; [truncated] says the body was capped and a marker was appended. None of these is ever a
 * raw JSON envelope (D-03) - that is the reader's whole job. A [body] that comes out blank is the
 * reader's way of saying the tool ran with nothing to show; the router (T-03) turns that into its
 * ran-no-output line.
 */
data class ToolResultDisplay(
    val summary: String,
    val body: String,
    val truncated: Boolean,
)

/**
 * Reads a `tool.completed` result into a [ToolResultDisplay] - pure, total, and tool-name-blind.
 *
 * A result arrives as whatever the tool returned, kept as a [JsonElement] on the wire (REQ-11). Three
 * shapes dominate, and the reader handles them by structure, never by which tool sent them:
 *
 *  - A **double-encoded** result: a JSON *string* whose text is itself JSON (execute_code returns its
 *    `{output, return_code}` this way). The reader parses the inner JSON and reads that (REQ-04).
 *  - A **bare string**: a JSON string that is not itself JSON. Used verbatim (REQ-04).
 *  - A **structured** result: a JSON object or array. Its text is pulled by a priority-ordered field
 *    heuristic over the keys tools commonly use for human output (REQ-05); a structure with none of
 *    those keys degrades to a readable flattening of its string values rather than showing its
 *    envelope (REQ-06).
 *
 * The body is then bounded to [BODY_CAP] with a visible marker (REQ-08). Every branch is guarded and
 * the JSON parse is caught, so the reader never throws on the tap it rides (the reply must not
 * stutter - the global constraint).
 */
object ToolResultReader {

    /** The most a body may show before it is cut and marked (REQ-08). Generous, but bounded. */
    internal const val BODY_CAP = 4_000

    /** Appended to a body the cap cut, so the wall says so plainly rather than lying about length. */
    internal const val TRUNCATION_MARKER = "\n… [truncated]"

    /** One line is at most this wide in the collapsed strip; the rest is behind the expand. */
    private const val SUMMARY_CAP = 120

    /**
     * Text keys tools commonly carry their human output under, most-specific first. The reader reads
     * the first one it finds a non-blank value for - by key set, never by tool name (REQ-05).
     */
    private val TEXT_KEYS = listOf("output", "stdout", "text", "content", "message", "error")

    fun read(result: JsonElement?, isError: Boolean): ToolResultDisplay {
        val body = try {
            extractText(result, isError).trim()
        } catch (_: Throwable) {
            // A total read: whatever the wire threw at us, it does not throw back out onto the tap.
            ""
        }

        if (body.isEmpty()) return ToolResultDisplay(summary = "", body = "", truncated = false)

        val truncated = body.length > BODY_CAP
        val bounded = if (truncated) body.take(BODY_CAP) + TRUNCATION_MARKER else body
        return ToolResultDisplay(summary = summarise(bounded), body = bounded, truncated = truncated)
    }

    /** The collapsed one-liner: the first non-blank line, whitespace-collapsed and width-capped. */
    private fun summarise(body: String): String {
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val collapsed = firstLine.replace(WHITESPACE, " ")
        return if (collapsed.length > SUMMARY_CAP) collapsed.take(SUMMARY_CAP - 1) + "…" else collapsed
    }

    /** Pull human text out of an element, recursing through the three shapes above. */
    private fun extractText(element: JsonElement?, isError: Boolean): String = when (element) {
        null, is JsonNull -> ""

        is JsonPrimitive ->
            if (element.isString) fromString(element.content, isError)
            else element.content // a bare number/boolean reads as itself, not as a JSON token

        is JsonObject -> fromObject(element, isError)

        is JsonArray -> element.joinToString("\n") { extractText(it, isError) }.trim()
    }

    /** A string value: itself JSON (un-double-encode) or plain text (verbatim). */
    private fun fromString(text: String, isError: Boolean): String {
        val nested = parseJsonContainer(text)
        return if (nested != null) extractText(nested, isError) else text
    }

    /**
     * An object: read the first recognised text key with a non-blank value; failing that, degrade to
     * a readable `key: value` flattening of its string-ish values - anything but the envelope (REQ-06).
     */
    private fun fromObject(obj: JsonObject, isError: Boolean): String {
        val keys = if (isError) listOf("error", "message") + TEXT_KEYS else TEXT_KEYS
        for (key in keys) {
            val value = obj[key] ?: continue
            val text = extractText(value, isError).trim()
            if (text.isNotEmpty()) return text
        }
        return flatten(obj)
    }

    /** A readable flattening: each scalar value on its own line, labelled, with no JSON punctuation. */
    private fun flatten(obj: JsonObject): String =
        obj.entries
            .mapNotNull { (key, value) -> scalar(value)?.let { "$key: $it" } }
            .joinToString("\n")

    /** The plain text of a scalar value, or null if it is a nested structure we would not flatten. */
    private fun scalar(value: JsonElement): String? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> value.content.takeUnless { it.isBlank() }
        else -> null
    }

    private val WHITESPACE = Regex("\\s+")
}
