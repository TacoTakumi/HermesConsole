package com.hermesconsole.voiceclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What VoiceManager says it is running (T-05, REQ-09): the active STT and TTS engines and the
 * models loaded behind them, off GET /info. Every field is optional with a null default - the
 * payload carries plenty this client has no use for, and a field the server has not populated
 * is a null, never an invention.
 */
@Serializable
data class VoiceManagerInfo(
    @SerialName("stt_engine") val sttEngine: String? = null,
    @SerialName("tts_engine") val ttsEngine: String? = null,
    val models: VoiceManagerModels = VoiceManagerModels(),
)

/** The loaded models by role, as /info's nested `models` object has them. Null = not loaded. */
@Serializable
data class VoiceManagerModels(
    val stt: String? = null,
    val tts: String? = null,
)

/**
 * One GET /info, on a deadline of its own: this feeds a wall readout refreshed on the health
 * poll's clock, and a hung host must cost one period, not OkHttp's patient default. Throws when
 * VoiceManager is not there or answers badly - the caller owns the verdict, exactly as the
 * Hermes probe's caller does.
 */
suspend fun fetchVoiceManagerInfo(
    config: VoiceManagerConfig,
    http: OkHttpClient = sharedInfoClient,
): VoiceManagerInfo = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(config.infoUrl()).header("Authorization", config.authorization).get().build()
    val call = http.newCall(request)
    call.timeout().timeout(INFO_DEADLINE_MS, TimeUnit.MILLISECONDS)
    call.execute().use { response ->
        if (!response.isSuccessful) throw IOException("VoiceManager /info answered HTTP ${response.code}")
        protocolJson.decodeFromString<VoiceManagerInfo>(response.body.string())
    }
}

/** /info needs the token like every VoiceManager call but /health; it goes in the header, not here. */
internal fun VoiceManagerConfig.infoUrl(): HttpUrl = HttpUrl.Builder()
    .scheme(if (tls) "https" else "http")
    .host(host)
    .port(port)
    .addPathSegments("info")
    .build()

/** One pooled client for every info fetch; a poller must not dial from a fresh pool each period. */
private val sharedInfoClient by lazy { OkHttpClient() }

private const val INFO_DEADLINE_MS = 3_000L
