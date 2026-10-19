package com.hermesconsole.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * When the wall console is rebuilt. The activity builds one console per emission of
 * [backendChanges], so what this flow emits is exactly what a save on the settings screen costs:
 * a change to where or how the console dials closes the running console and starts a new one, and
 * a change to anything else - the voice level, the clocks, the follow-up window, recordable audio -
 * must not, because a rebuild drops the mic and the backends' links for a moment the room can hear.
 */
class ConsoleRebuildTest {

    private val complete = BackendSettings(
        hermesHost = "10.0.0.5",
        hermesPort = 9001,
        hermesKey = "hermes-key-under-test",
        vmHost = "10.0.0.6",
        vmPort = 9002,
        vmKey = "vm-key-under-test",
    )

    /** Every setting toBackends reads left as it was, everything else changed. */
    private val sameConnection = complete.copy(
        followUpWindowSeconds = 25,
        sessionTimeoutSeconds = 90,
        screensaverTimeoutSeconds = 150,
        voiceVolumePercent = 40,
        recordableAudio = true,
    )

    @Test
    fun `incomplete settings build nothing`() = runTest {
        val builds = flowOf(BackendSettings()).backendChanges().toList()

        assertEquals(listOf(null), builds)
    }

    @Test
    fun `an unconfigured console builds once when complete settings arrive`() = runTest {
        val settings = MutableStateFlow(BackendSettings())
        val builds = mutableListOf<DebugBackends?>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            settings.backendChanges().collect { builds += it }
        }

        // Half typed in: a host but no keys yet is still nothing to dial.
        settings.value = BackendSettings(hermesHost = "10.0.0.5")
        settings.value = complete

        assertEquals(2, builds.size)
        assertNull(builds[0])
        assertEquals(complete.toBackends(), builds[1])
        collecting.cancel()
    }

    @Test
    fun `a change outside the connection fields does not rebuild`() = runTest {
        val builds = flowOf(complete, sameConnection).backendChanges().toList()

        assertEquals(listOf(complete.toBackends()), builds)
    }

    @Test
    fun `a change to a connection field rebuilds`() = runTest {
        val newPort = complete.copy(vmPort = 9003)
        val newKey = newPort.copy(hermesKey = "rotated-key")

        val builds = flowOf(complete, newPort, newKey).backendChanges().toList()

        assertEquals(listOf(complete.toBackends(), newPort.toBackends(), newKey.toBackends()), builds)
    }

    @Test
    fun `a console whose settings become incomplete is closed and not rebuilt`() = runTest {
        val builds = flowOf(complete, complete.copy(vmKey = ""), BackendSettings()).backendChanges().toList()

        assertEquals(2, builds.size)
        assertNotNull(builds[0])
        assertNull(builds[1])
    }
}
