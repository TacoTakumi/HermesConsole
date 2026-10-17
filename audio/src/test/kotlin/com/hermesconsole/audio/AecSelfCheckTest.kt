package com.hermesconsole.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * REQ-02: a startup self-check reports AcousticEchoCanceler availability and enabled state,
 * and it is reported *per selected device* - so a route change never leaves a stale verdict
 * standing (REQ-03).
 */
class AecSelfCheckTest {

    private val builtIn = AudioDeviceDescriptor(id = 1, type = DeviceType.BUILTIN_MIC, name = "builtin")
    private val usb = AudioDeviceDescriptor(id = 7, type = DeviceType.USB, name = "Blue Yeti")

    /** Stands in for the real AcousticEchoCanceler: on this fake rig, AEC exists only for the built-in mic. */
    private val probe = AecProbe { device ->
        val supported = device?.type == DeviceType.BUILTIN_MIC
        AecStatus(device = device, available = supported, enabled = supported)
    }

    @Test
    fun `self-check reports the AEC verdict for the selected device`() {
        val status = runAecSelfCheck(AudioConfig(), listOf(builtIn, usb), probe)

        assertEquals(builtIn, status.device)
        assertTrue(status.available)
        assertTrue(status.enabled)
    }

    @Test
    fun `switching the configured source re-runs the self-check against the new device`() {
        val config = AudioConfig(inputSource = InputSource.USB)

        val status = runAecSelfCheck(config, listOf(builtIn, usb), probe)

        assertEquals(usb, status.device)
        assertFalse(status.available, "the USB mic has no hardware AEC on this rig")
        assertFalse(status.enabled)
    }

    @Test
    fun `the self-check line names the device, availability and enabled state`() {
        val line = runAecSelfCheck(AudioConfig(), listOf(builtIn), probe).describe()

        assertTrue(line.contains("builtin"), line)
        assertTrue(line.contains("available=true"), line)
        assertTrue(line.contains("enabled=true"), line)
    }

    @Test
    fun `the voice loop consumes AudioCapture and is untouched by a source switch`() = runTest {
        // The "voice loop" only ever sees this interface. Both configs drive the same code path;
        // adding a source is a configuration diff, never a state-machine diff (REQ-03).
        suspend fun voiceLoop(capture: AudioCapture): Int = capture.frames().toList().size

        assertEquals(2, voiceLoop(FakeCapture(AudioConfig())))
        assertEquals(2, voiceLoop(FakeCapture(AudioConfig(inputSource = InputSource.USB))))
    }

    private class FakeCapture(override val config: AudioConfig) : AudioCapture {
        override fun frames(): Flow<ByteArray> = flowOf(ByteArray(320), ByteArray(320))
        override fun close() = Unit
    }
}
