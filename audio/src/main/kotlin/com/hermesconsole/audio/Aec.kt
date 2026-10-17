package com.hermesconsole.audio

/**
 * The verdict of the acoustic-echo-canceler self-check for one capture device.
 *
 * This is the fact the whole interaction model rests on (D-03, REQ-05): if hardware AEC is not
 * available and enabled on the device we are capturing from, the tablet hears its own TTS and
 * barges in on itself. It is checked at startup and re-checked whenever the route changes -
 * never assumed to still hold.
 */
data class AecStatus(
    val device: AudioDeviceDescriptor?,
    val available: Boolean,
    val enabled: Boolean,
) {
    fun describe(): String =
        "AEC self-check: device=${device?.name ?: "none"} (id=${device?.id ?: -1}) " +
            "available=$available enabled=$enabled"
}

/** Asks the platform for the AEC verdict on a device. Backed by AcousticEchoCanceler on-device. */
fun interface AecProbe {
    fun probe(device: AudioDeviceDescriptor?): AecStatus
}

/** Resolve the configured input source, then report the AEC verdict for the device we landed on. */
fun runAecSelfCheck(
    config: AudioConfig,
    available: List<AudioDeviceDescriptor>,
    probe: AecProbe,
): AecStatus = probe.probe(selectInputDevice(config.inputSource, available))

/**
 * What we know about barge-in on the currently configured output route (REQ-34).
 *
 * [bargeInMeasured] is the honest part: the hardware gate (T-04) measures echo cancellation with
 * TTS coming out of the tablet's own speakers. Route the audio somewhere else - a soundbar, a
 * Bluetooth speaker across the room - and that measurement no longer describes reality. The AEC
 * self-check is re-run for the new route and the verdict says so, rather than quietly assuming
 * the gate still holds.
 */
data class RouteVerdict(
    val route: OutputRoute,
    val aec: AecStatus,
    val bargeInMeasured: Boolean,
) {
    fun describe(): String =
        "output route=$route bargeInMeasured=$bargeInMeasured; ${aec.describe()}"
}

/** Re-run the AEC self-check for the configured output route and report what it actually found. */
fun verifyOutputRoute(
    config: AudioConfig,
    inputDevices: List<AudioDeviceDescriptor>,
    probe: AecProbe,
): RouteVerdict = RouteVerdict(
    route = config.outputRoute,
    aec = runAecSelfCheck(config, inputDevices, probe),
    bargeInMeasured = config.outputRoute == OutputRoute.BUILTIN_SPEAKER,
)
