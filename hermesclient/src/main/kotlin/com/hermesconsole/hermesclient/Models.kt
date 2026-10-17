package com.hermesconsole.hermesclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The gateway's advertised identity (T-06, REQ-07): GET /v1/models, whose primary entry's id is
 * the active profile's name - each profile advertises a distinct model, with any configured route
 * aliases listed behind it (api_server.py `_handle_models`).
 */

@Serializable
internal data class ModelList(val data: List<AdvertisedModel> = emptyList())

/** One advertised model. Only the id matters here; the rest of the entry is OpenAI furniture. */
@Serializable
internal data class AdvertisedModel(val id: String)

/**
 * The advertised profile name: the primary model id, never an alias. Null when the gateway
 * advertises nothing - the readout's placeholder, not an invented name. Throws when the gateway
 * is not there or answers badly; on a deadline of its own, like [probe], because this rides the
 * health poll's clock and a hung host must cost one period, not OkHttp's patient default.
 */
suspend fun HermesClient.advertisedProfile(): String? = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(config.modelsUrl())
        .header("Authorization", config.authorization)
        .get()
        .build()
    val call = http.newCall(request)
    call.timeout().timeout(PROFILE_DEADLINE_MS, TimeUnit.MILLISECONDS)
    call.execute().use { response ->
        if (!response.isSuccessful) throw response.toFailure()
        hermesJson.decodeFromString<ModelList>(response.body.string()).data.firstOrNull()?.id
    }
}

/** GET /api/model/options is the dashboard picker's catalog; only the selection matters here. */
@Serializable
internal data class ModelOptions(val model: String? = null)

/**
 * The gateway's configured model selection (2026-08-14): what it intends to serve with, known
 * the moment the gateway is reachable - unlike the effective model, which only a completed run
 * reports. Null when nothing is configured - the readout's placeholder, not an invented name.
 * Throws and deadlines exactly like [advertisedProfile], whose poll family it rides.
 */
suspend fun HermesClient.configuredModel(): String? = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(config.modelOptionsUrl())
        .header("Authorization", config.authorization)
        .get()
        .build()
    val call = http.newCall(request)
    call.timeout().timeout(PROFILE_DEADLINE_MS, TimeUnit.MILLISECONDS)
    call.execute().use { response ->
        if (!response.isSuccessful) throw response.toFailure()
        hermesJson.decodeFromString<ModelOptions>(response.body.string()).model?.takeIf { it.isNotBlank() }
    }
}

private const val PROFILE_DEADLINE_MS = 3_000L
