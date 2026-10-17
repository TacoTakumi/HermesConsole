package com.hermesconsole.audio

/** A capture device, described without reference to any Android type so selection stays testable. */
data class AudioDeviceDescriptor(
    val id: Int,
    val type: DeviceType,
    val name: String,
)

enum class DeviceType { BUILTIN_MIC, BUILTIN_SPEAKER, USB, BLUETOOTH, WIRED_HEADSET, OTHER }

/** The configurable capture sources (REQ-03). Adding one is a change here, and nowhere else. */
enum class InputSource(val deviceType: DeviceType) {
    BUILTIN_MIC(DeviceType.BUILTIN_MIC),
    USB(DeviceType.USB),
    BLUETOOTH(DeviceType.BLUETOOTH),
    WIRED_HEADSET(DeviceType.WIRED_HEADSET),
}

/**
 * The configurable playback routes (REQ-34). v1 plays through the tablet's own speakers - the
 * one route the barge-in gate is measured in.
 */
enum class OutputRoute(val deviceType: DeviceType) {
    BUILTIN_SPEAKER(DeviceType.BUILTIN_SPEAKER),
    USB(DeviceType.USB),
    BLUETOOTH(DeviceType.BLUETOOTH),
    WIRED_HEADSET(DeviceType.WIRED_HEADSET),
}

/**
 * Resolve the configured source against what is actually plugged in.
 *
 * Returns null when the configured source is absent rather than falling back to the built-in mic:
 * a silent fallback would hide a dead USB mic behind a working-looking capture stream, and the
 * AEC verdict for the device we *meant* to use would never be re-run.
 */
fun selectInputDevice(
    source: InputSource,
    available: List<AudioDeviceDescriptor>,
): AudioDeviceDescriptor? = available.firstOrNull { it.type == source.deviceType }

/**
 * Whether to override the platform's own capture routing for this source.
 *
 * Only for an explicitly configured external device. On the built-in mic we must NOT: the
 * voice-communication path routes itself to the mic its echo canceller is calibrated for, and
 * pinning a device overrides that - measured on the SM-X210 as a capture stream starved to a
 * third of real time and ~30 dB down (T-04, docs/aec-spike.md).
 */
fun shouldPinDevice(source: InputSource): Boolean = source != InputSource.BUILTIN_MIC

/** Resolve the configured playback route against the attached output devices (REQ-34). */
fun selectOutputDevice(
    route: OutputRoute,
    available: List<AudioDeviceDescriptor>,
): AudioDeviceDescriptor? = available.firstOrNull { it.type == route.deviceType }
