package com.hermesconsole.hermesclient

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Lenient on purpose: a tool's own output is not a protocol frame, so loose JSON still reads. */
private val containerJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * [text] parsed as JSON when it is a JSON object or array, else null. A tool result often arrives
 * as a string whose text is itself JSON (a double-encoded result); a bare scalar, plain text, or a
 * container that does not parse is not "itself JSON" and gives null. Never throws.
 */
fun parseJsonContainer(text: String): JsonElement? {
    val trimmed = text.trimStart()
    if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return null
    return try {
        containerJson.parseToJsonElement(text).takeIf { it is JsonObject || it is JsonArray }
    } catch (_: Throwable) {
        null
    }
}
