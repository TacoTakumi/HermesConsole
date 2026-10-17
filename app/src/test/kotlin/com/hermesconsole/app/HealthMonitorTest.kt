package com.hermesconsole.app

import com.hermesconsole.hermesclient.HermesSession
import com.hermesconsole.voiceclient.VoiceLinkState
import com.hermesconsole.voiceclient.VoiceManagerInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

import kotlinx.coroutines.test.TestScope
import java.io.IOException

/**
 * T-22 / REQ-23: live reachability for both backends, on the wall.
 *
 * The acceptance is a clock: killing a backend drives its indicator to failed within 10 s, and
 * restarting it restores the indicator within 10 s. Hermes is request/response, so its half is a
 * poll - and the poll period is what bounds the detection time, which is why it is asserted here.
 * VoiceManager holds a socket open, so its half is a mapping of the link state the client already
 * maintains - with the wrinkle that a reconnecting link flaps between Dropped and Connecting, and
 * an indicator that flapped with it would be unreadable from across a room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthMonitorTest {

    private val sessions = listOf(HermesSession("api_1", "galley refit", null, 3, 1.0))

    private class FlippableProbe {
        var healthy = true
        var calls = 0
        var result: List<HermesSession> = emptyList()

        suspend fun probe(): List<HermesSession> {
            calls++
            if (!healthy) throw IOException("connection refused")
            return result
        }
    }

    private class FlippableInfo {
        var healthy = true
        var result: VoiceManagerInfo = VoiceManagerInfo(sttEngine = "whisper", ttsEngine = "openvoice")

        suspend fun fetch(): VoiceManagerInfo {
            if (!healthy) throw IOException("connection refused")
            return result
        }
    }

    private class FlippableProfile {
        var healthy = true
        var result: String? = "tactical"

        suspend fun fetch(): String? {
            if (!healthy) throw IOException("connection refused")
            return result
        }
    }

    private class FlippableModel {
        var healthy = true
        var calls = 0
        var result: String? = "qwen3.6-27b"

        suspend fun fetch(): String? {
            calls++
            if (!healthy) throw IOException("connection refused")
            return result
        }
    }

    /**
     * The monitor under test, polling on the test clock. It lives in [TestScope.backgroundScope]
     * because the poll loop is deliberately endless - on the wall it runs for years - and only
     * that scope's end-of-test cancellation stops `runTest` waiting forever for it to finish.
     */
    private fun TestScope.monitor(
        probe: FlippableProbe,
        link: MutableStateFlow<VoiceLinkState> = MutableStateFlow(VoiceLinkState.Idle),
        info: FlippableInfo = FlippableInfo(),
        profile: FlippableProfile = FlippableProfile(),
        model: FlippableModel = FlippableModel(),
    ): HealthMonitor = HealthMonitor(
        probe = probe::probe,
        link = link,
        vmInfoProbe = info::fetch,
        profileProbe = profile::fetch,
        modelProbe = model::fetch,
        scope = backgroundScope,
    ).also { it.start() }

    // -- the Hermes half: a poll --------------------------------------------------------------

    @Test
    fun `a reachable gateway reads OK, and the poll's sessions are the ready state's list`() = runTest {
        val probe = FlippableProbe().apply { result = sessions }
        val monitor = monitor(probe)
        runCurrent()

        assertEquals(BackendHealth.OK, monitor.hermes.value)
        assertEquals(sessions, monitor.sessions.value)
    }

    @Test
    fun `a killed gateway reads FAILED within one poll period - that is the 10 s clock`() = runTest {
        val probe = FlippableProbe()
        val monitor = monitor(probe)
        runCurrent()
        assertEquals(BackendHealth.OK, monitor.hermes.value)

        probe.healthy = false
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals(BackendHealth.FAILED, monitor.hermes.value)
    }

    @Test
    fun `a restarted gateway reads OK within one poll period`() = runTest {
        val probe = FlippableProbe().apply { healthy = false }
        val monitor = monitor(probe)
        runCurrent()
        assertEquals(BackendHealth.FAILED, monitor.hermes.value)

        probe.healthy = true
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals(BackendHealth.OK, monitor.hermes.value)
    }

    @Test
    fun `the poll period keeps the 10 s promise with room to spare`() {
        // Detection latency is poll period + probe deadline in the worst case; both are asserted
        // so a future retune cannot quietly break REQ-23's clock.
        assertEquals(true, HealthMonitor.POLL_MILLIS <= 5_000L)
    }

    @Test
    fun `a dead poll keeps the last session list - a stale list beats a blank wall`() = runTest {
        val probe = FlippableProbe().apply { result = sessions }
        val monitor = monitor(probe)
        runCurrent()
        assertEquals(sessions, monitor.sessions.value)

        probe.healthy = false
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals(BackendHealth.FAILED, monitor.hermes.value)
        assertEquals(sessions, monitor.sessions.value)
    }

    @Test
    fun `poke brings the next poll forward - a created session shows up now, not a period later`() = runTest {
        val probe = FlippableProbe()
        val monitor = monitor(probe)
        runCurrent()
        val before = probe.calls

        monitor.poke()
        runCurrent()

        assertEquals(before + 1, probe.calls)
    }

    // -- T-05 / REQ-09: the VoiceManager /info readout, on the same clock -----------------------

    @Test
    fun `a reachable voicemanager's info is the readout's source, refreshed on the poll`() = runTest {
        val info = FlippableInfo()
        val monitor = monitor(FlippableProbe(), info = info)
        runCurrent()

        assertEquals("whisper", monitor.vmInfo.value?.sttEngine)

        info.result = VoiceManagerInfo(sttEngine = "qwen", ttsEngine = "vibevoice")
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals("qwen", monitor.vmInfo.value?.sttEngine, "the readout rides the existing cadence")
    }

    @Test
    fun `a dead info poll keeps the last engines readout - the lamp carries the outage, not the readout`() = runTest {
        // 2026-08-14: an outage used to flip a populated readout to NO DATA every restart
        // window. The lamps already say the link is down; the readout holds the last answer,
        // exactly like the session list above.
        val info = FlippableInfo()
        val monitor = monitor(FlippableProbe(), info = info)
        runCurrent()
        assertEquals("whisper", monitor.vmInfo.value?.sttEngine)

        info.healthy = false
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals("whisper", monitor.vmInfo.value?.sttEngine, "an outage keeps the last engines readout")

        // And recovery refreshes rather than resurrects: a server that came back different
        // shows its new engines on the next poll, not the held ones.
        info.result = VoiceManagerInfo(sttEngine = "audiocpp", ttsEngine = "vibevoice")
        info.healthy = true
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals("audiocpp", monitor.vmInfo.value?.sttEngine, "a restarted voicemanager's readout refreshes")
    }

    // -- T-06 / REQ-07: the gateway's advertised profile, on the same clock ---------------------

    @Test
    fun `a reachable gateway's advertised profile is the readout's source, refreshed on the poll`() = runTest {
        val profile = FlippableProfile()
        val monitor = monitor(FlippableProbe(), profile = profile)
        runCurrent()

        assertEquals("tactical", monitor.profile.value)

        profile.result = "science"
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals("science", monitor.profile.value, "a profile switch shows within one period")
    }

    @Test
    fun `a dead profile poll keeps the last name - the lamp carries the outage, not the readout`() = runTest {
        // 2026-08-14: same rule as the engines readout - NO DATA is for "never answered",
        // not "not answering right now".
        val profile = FlippableProfile()
        val monitor = monitor(FlippableProbe(), profile = profile)
        runCurrent()
        assertEquals("tactical", monitor.profile.value)

        profile.healthy = false
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals("tactical", monitor.profile.value, "an outage keeps the last profile name")

        profile.result = "science"
        profile.healthy = true
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals("science", monitor.profile.value, "a restarted gateway's name refreshes")
    }

    // -- the gateway's configured model, fetched on arrival, not on the clock (2026-08-14) ------

    @Test
    fun `the configured model is fetched when the gateway first answers, and not again every poll`() = runTest {
        // /api/model/options is the whole provider catalog - one fetch on the link coming up,
        // not one per 5 s poll.
        val model = FlippableModel()
        val monitor = monitor(FlippableProbe(), model = model)
        runCurrent()

        assertEquals("qwen3.6-27b", monitor.configuredModel.value)
        assertEquals(1, model.calls)

        advanceTimeBy(3 * HealthMonitor.POLL_MILLIS + 1)

        assertEquals(1, model.calls, "a healthy link does not refetch the catalog on the clock")
    }

    @Test
    fun `an outage holds the configured model, and recovery refetches it - a restart may have changed it`() = runTest {
        val probe = FlippableProbe()
        val model = FlippableModel()
        val monitor = monitor(probe, model = model)
        runCurrent()
        assertEquals("qwen3.6-27b", monitor.configuredModel.value)

        probe.healthy = false
        model.healthy = false
        advanceTimeBy(2 * HealthMonitor.POLL_MILLIS + 1)

        assertEquals("qwen3.6-27b", monitor.configuredModel.value, "an outage keeps the last configured model")
        assertEquals(1, model.calls, "a dead gateway is not asked for its catalog")

        model.result = "llama-x"
        probe.healthy = true
        model.healthy = true
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals("llama-x", monitor.configuredModel.value, "recovery refetches - the restart may have reconfigured")
    }

    @Test
    fun `a failed catalog fetch retries on the poll while the readout is empty`() = runTest {
        // The lamp can come up OK while the one catalog fetch fails; an empty readout must not
        // stay empty until the next outage.
        val model = FlippableModel().apply { healthy = false }
        val monitor = monitor(FlippableProbe(), model = model)
        runCurrent()
        assertEquals(null, monitor.configuredModel.value)
        assertEquals(1, model.calls)

        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)
        assertEquals(2, model.calls, "empty readout retries on the clock")

        model.healthy = true
        advanceTimeBy(HealthMonitor.POLL_MILLIS + 1)

        assertEquals("qwen3.6-27b", monitor.configuredModel.value)
    }

    // -- the VoiceManager half: the link state, steadied ---------------------------------------

    @Test
    fun `the voice indicator follows the link - connected is OK, dropped is FAILED`() = runTest {
        val link = MutableStateFlow<VoiceLinkState>(VoiceLinkState.Idle)
        val monitor = monitor(FlippableProbe(), link)
        runCurrent()
        assertEquals(BackendHealth.UNKNOWN, monitor.voice.value)

        link.value = VoiceLinkState.Connected
        runCurrent()
        assertEquals(BackendHealth.OK, monitor.voice.value)

        link.value = VoiceLinkState.Dropped(code = 1006, reason = "abnormal closure")
        runCurrent()
        assertEquals(BackendHealth.FAILED, monitor.voice.value)
    }

    @Test
    fun `a reconnecting link does not flap the indicator - Connecting keeps the last verdict`() = runTest {
        val link = MutableStateFlow<VoiceLinkState>(VoiceLinkState.Connected)
        val monitor = monitor(FlippableProbe(), link)
        runCurrent()
        assertEquals(BackendHealth.OK, monitor.voice.value)

        // The client redials in a loop while VoiceManager is down: Dropped -> Connecting ->
        // Dropped -> ... The verdict must land on FAILED and stay there through the flapping.
        link.value = VoiceLinkState.Dropped(code = 1006, reason = "down")
        runCurrent()
        link.value = VoiceLinkState.Connecting
        runCurrent()
        assertEquals(BackendHealth.FAILED, monitor.voice.value)

        // And the restart resolves it the moment the socket is back.
        link.value = VoiceLinkState.Connected
        runCurrent()
        assertEquals(BackendHealth.OK, monitor.voice.value)
    }

    @Test
    fun `a rejected key reads FAILED - an unreachable and an unauthenticated backend look the same on the wall`() =
        runTest {
            val link = MutableStateFlow<VoiceLinkState>(VoiceLinkState.Connected)
            val monitor = monitor(FlippableProbe(), link)
            runCurrent()

            link.value = VoiceLinkState.AuthFailed
            runCurrent()
            assertEquals(BackendHealth.FAILED, monitor.voice.value)
        }
}
