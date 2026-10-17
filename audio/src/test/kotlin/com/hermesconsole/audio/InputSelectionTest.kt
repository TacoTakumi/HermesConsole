package com.hermesconsole.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * REQ-03: the capture input source is runtime configuration. Switching it selects a different
 * device and re-runs the AEC self-check for that device - with no change to voice-loop code.
 *
 * These run on a plain JVM: selection is pure logic over [AudioDeviceDescriptor], and the
 * AudioRecord / AcousticEchoCanceler calls sit behind [AudioCapture] where the loop can't see them.
 */
class InputSelectionTest {

    private val builtIn = AudioDeviceDescriptor(id = 1, type = DeviceType.BUILTIN_MIC, name = "builtin")
    private val usb = AudioDeviceDescriptor(id = 7, type = DeviceType.USB, name = "Blue Yeti")
    private val bluetooth = AudioDeviceDescriptor(id = 9, type = DeviceType.BLUETOOTH, name = "buds")
    private val available = listOf(builtIn, usb, bluetooth)

    @Test
    fun `defaults to the built-in mic`() {
        val config = AudioConfig()
        assertEquals(InputSource.BUILTIN_MIC, config.inputSource)
        assertEquals(builtIn, selectInputDevice(config.inputSource, available))
    }

    @Test
    fun `switching the configured source selects a different device`() {
        val default = selectInputDevice(AudioConfig().inputSource, available)
        val switched = selectInputDevice(AudioConfig(inputSource = InputSource.USB).inputSource, available)

        assertEquals(builtIn, default)
        assertEquals(usb, switched)
    }

    @Test
    fun `every configurable source resolves to its own device`() {
        assertEquals(builtIn, selectInputDevice(InputSource.BUILTIN_MIC, available))
        assertEquals(usb, selectInputDevice(InputSource.USB, available))
        assertEquals(bluetooth, selectInputDevice(InputSource.BLUETOOTH, available))
    }

    @Test
    fun `an unplugged configured source selects nothing rather than silently falling back`() {
        // Silently falling back to the built-in mic would hide a dead USB mic behind a working
        // capture stream; the caller is told instead (and re-runs the self-check on what it got).
        assertNull(selectInputDevice(InputSource.USB, listOf(builtIn)))
    }

    @Test
    fun `the built-in mic leaves the platform's voice routing alone`() {
        // Pinning the device on the built-in mic breaks the voice-communication capture path on
        // the SM-X210 (starved stream, 30 dB down). The platform routes its own voice pipeline.
        assertFalse(shouldPinDevice(InputSource.BUILTIN_MIC))
    }

    @Test
    fun `an explicitly configured external mic is pinned`() {
        assertTrue(shouldPinDevice(InputSource.USB))
        assertTrue(shouldPinDevice(InputSource.BLUETOOTH))
        assertTrue(shouldPinDevice(InputSource.WIRED_HEADSET))
    }

    @Test
    fun `the capture format is 16 kHz mono 16-bit PCM`() {
        val config = AudioConfig()
        assertEquals(16_000, config.sampleRateHz)
        assertEquals(1, config.channelCount)
        assertEquals(16, config.bitsPerSample)
    }
}
